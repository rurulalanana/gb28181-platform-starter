/**
 * 浏览器端 MP4 → fMP4（分段 MP4）重封装器。
 *
 * <h2>为什么需要它</h2>
 * <p>ZLMediaKit 录出来的 H.265 MP4 有一个特性让浏览器的硬解码器挂掉：
 * 码流里有一帧解不了（ffmpeg 也会跳过它、只解出 1104/1105 帧），
 * 而 Chrome 的 HEVC 硬解<b>不容错</b>，遇到就 fatal
 * （{@code PIPELINE_ERROR_DECODE}），只出首帧就停。
 *
 * <p>实测（真 Chrome 158，2560x1440 H.265 Main，44.6s 录像）：
 * <pre>
 *   原生 &lt;video src&gt; + 普通 MP4  → 0.72s 崩
 *   原生 &lt;video src&gt; + 仅重封装    → 5.67s 崩（码流语义没变，没用）
 *   MSE + 普通 MP4                  → 21.0s 崩
 *   MSE + fMP4（按 IDR 切片段）       → 21.0s 崩，但跳过坏片段后 → 43.3s 全程正常 ✅
 * </pre>
 *
 * <p><b>关键在 {@code frag_keyframe}</b>：每个片段都从 IDR 开始，
 * 片段之间彼此独立可解码 —— 于是只要把含坏帧的那个片段丢掉，其余照播。
 *
 * <h2>本类做什么</h2>
 * <p><b>只换容器，不转码</b>：把原始 MP4 的样本按 GOP 重新包装成
 * {@code moof + mdat} 片段，编码参数、码率、帧内容一字不改。
 *
 * <h2>为什么放在浏览器里做</h2>
 * <ul>
 *   <li>服务端完全不用改 —— 直接用现成的 {@code /cloudrecord/file/{recordId}}
 *       （它本来就支持 HTTP Range）；</li>
 *   <li>不引入服务端转码开销（实测服务端转码 44s 的 1440p 视频要 51 秒，
 *       比播放本身还慢）；</li>
 *   <li>编码信息（hvcC / avcC）直接从原始文件里读，不用另猜。</li>
 * </ul>
 *
 * @module Mp4Remuxer
 */

/** 视频轨的 sample entry fourcc —— H.264。 */
export const CODEC_H264 = 'h264';
/** 视频轨的 sample entry fourcc —— H.265 / HEVC。 */
export const CODEC_H265 = 'h265';
/** 不是已知的视频编码。 */
export const CODEC_UNKNOWN = 'unknown';

const FOURCC_H264 = ['avc1', 'avc3'];
const FOURCC_H265 = ['hvc1', 'hev1'];

/**
 * 解析 box 列表。
 *
 * @param {Uint8Array} buf  缓冲区
 * @param {number} start    起始偏移
 * @param {number} end      结束偏移
 * @returns {Array<{type:string,start:number,bodyStart:number,end:number,size:number,headerSize:number}>}
 */
export function parseBoxes(buf, start = 0, end = buf.length) {
  const out = [];
  let off = start;
  while (off + 8 <= end) {
    let size = buf[off] << 24 | buf[off + 1] << 16 | buf[off + 2] << 8 | buf[off + 3];
    const type = String.fromCharCode(buf[off + 4], buf[off + 5], buf[off + 6], buf[off + 7]);
    let headerSize = 8;
    if (size === 1) {
      // 64 位长度：high 32 位在前
      const hi = buf[off + 8] << 24 | buf[off + 9] << 16 | buf[off + 10] << 8 | buf[off + 11];
      const lo = buf[off + 12] << 24 | buf[off + 13] << 16 | buf[off + 14] << 8 | buf[off + 15];
      size = hi * 4294967296 + lo;
      headerSize = 16;
    } else if (size === 0) {
      size = end - off;
    }
    if (size < headerSize || off + size > end) {
      break;
    }
    out.push({ type, start: off, bodyStart: off + headerSize, end: off + size, size, headerSize });
    off += size;
  }
  return out;
}

/**
 * 在 box 树里按路径查找（路径不含首层）。
 *
 * @param {Uint8Array} buf  缓冲区
 * @param {number} start    区域起始
 * @param {number} end      区域结束
 * @param {string[]} path   box 类型路径，如 {@code ['mdia','minf','stbl','stsz']}
 * @returns {object|null}
 */
function findPath(buf, start, end, path) {
  const boxes = parseBoxes(buf, start, end);
  for (const want of path) {
    const hit = boxes.find(b => b.type === want);
    if (!hit) {
      return null;
    }
    if (want === path[path.length - 1]) {
      return hit;
    }
    // 继续往下找：只在容器 box 里递归
    const deeper = findDirect(buf, hit.bodyStart, hit.end, want);
    if (deeper) {
      return deeper;
    }
    return null;
  }
  return null;
}

/** 在一层里找指定类型。 */
function findDirect(buf, start, end, type) {
  return parseBoxes(buf, start, end).find(b => b.type === type) || null;
}

/** 递归查找（含容器下钻）。 */
function findRecursive(buf, start, end, type, containerTypes) {
  for (const b of parseBoxes(buf, start, end)) {
    if (b.type === type) {
      return b;
    }
    if (containerTypes.includes(b.type)) {
      const r = findRecursive(buf, b.bodyStart, b.end, type, containerTypes);
      if (r) {
        return r;
      }
    }
  }
  return null;
}

/** 读 uint32。 */
function u32(buf, off) {
  return (buf[off] << 24 | buf[off + 1] << 16 | buf[off + 2] << 8 | buf[off + 3]) >>> 0;
}

/** 读 uint64（返回 Number，够用）。 */
function u64(buf, off) {
  const hi = u32(buf, off);
  const lo = u32(buf, off + 4);
  return hi * 4294967296 + lo;
}

/** 拼一个 box。 */
function makeBox(type, payload) {
  const out = new Uint8Array(8 + payload.length);
  const size = out.length;
  out[0] = size >>> 24; out[1] = (size >>> 16) & 0xff; out[2] = (size >>> 8) & 0xff; out[3] = size & 0xff;
  out[4] = type.charCodeAt(0); out[5] = type.charCodeAt(1); out[6] = type.charCodeAt(2); out[7] = type.charCodeAt(3);
  out.set(payload, 8);
  return out;
}

/** 拼一个 full box（version + flags 占 4 字节）。 */
function makeFullBox(type, version, flags, payload) {
  const head = new Uint8Array(4);
  head[0] = version;
  head[1] = (flags >>> 16) & 0xff; head[2] = (flags >>> 8) & 0xff; head[3] = flags & 0xff;
  const body = new Uint8Array(head.length + payload.length);
  body.set(head, 0);
  body.set(payload, head.length);
  return makeBox(type, body);
}

/**
 * MP4 重封装器。
 *
 * <p>用法：
 * <pre>
 *   const remuxer = await Mp4Remuxer.create(fileBytes);
 *   const init = remuxer.initSegment();          // ftyp + moov
 *   for (const gop of remuxer.gops()) {
 *     const frag = remuxer.fragment(gop);         // moof + mdat
 *   }
 * </pre>
 */
export class Mp4Remuxer {
  /**
   * @param {Uint8Array} buf  整个 MP4 文件（或至少包含 moov 的部分）
   */
  constructor(buf) {
    this.buf = buf;
    this.boxes = parseBoxes(buf, 0, buf.length);
    this.ftypBox = this.boxes.find(b => b.type === 'ftyp') || null;
    this.moovBox = this.boxes.find(b => b.type === 'moov') || null;
    if (!this.moovBox) {
      throw new Error('找不到 moov box（不是有效的 MP4）');
    }
    this._parseVideoTrack();
  }

  /**
   * 解析视频轨信息。
   */
  _parseVideoTrack() {
    const moov = this.moovBox;
    const traks = parseBoxes(this.buf, moov.bodyStart, moov.end).filter(b => b.type === 'trak');
    for (const trak of traks) {
      const stsd = findRecursive(this.buf, trak.bodyStart, trak.end, 'stsd', ['mdia', 'minf', 'stbl']);
      if (!stsd) {
        continue;
      }
      // stsd：version/flags(4) + entry_count(4)，随后是 sample entry box
      const entryCount = u32(this.buf, stsd.bodyStart + 4);
      const entries = parseBoxes(this.buf, stsd.bodyStart + 8, stsd.end);
      for (const entry of entries.slice(0, entryCount)) {
        const fourcc = String.fromCharCode(this.buf[entry.start + 4], this.buf[entry.start + 5],
          this.buf[entry.start + 6], this.buf[entry.start + 7]);
        if (FOURCC_H264.includes(fourcc) || FOURCC_H265.includes(fourcc)) {
          this._readTrack(trak, stsd, fourcc);
          return;
        }
      }
    }
    throw new Error('找不到视频轨（既不是 avc1 也不是 hvc1）');
  }

  /**
   * 读取某个视频轨的 sample table。
   *
   * @param {object} trak   trak box
   * @param {object} stsd   stsd box
   * @param {string} fourcc sample entry 类型
   */
  _readTrack(trak, stsd, fourcc) {
    const buf = this.buf;
    this.fourcc = fourcc;
    this.codec = FOURCC_H264.includes(fourcc) ? CODEC_H264 : CODEC_H265;

    // 轨道号（tkhd / trex 都要用）
    const tkhd = findRecursive(buf, trak.bodyStart, trak.end, 'tkhd', ['mdia']);
    const tkhdVersion = tkhd ? buf[tkhd.bodyStart] : 0;
    this.trackId = tkhd
      ? (tkhdVersion === 1 ? u32(buf, tkhd.bodyStart + 20) : u32(buf, tkhd.bodyStart + 12))
      : 1;

    // 时间刻度
    const mdhd = findRecursive(buf, trak.bodyStart, trak.end, 'mdhd', ['mdia']);
    const mdhdVersion = mdhd ? buf[mdhd.bodyStart] : 0;
    this.timescale = mdhd
      ? (mdhdVersion === 1 ? u32(buf, mdhd.bodyStart + 20) : u32(buf, mdhd.bodyStart + 12))
      : 1000;

    // 样本大小
    const stsz = findRecursive(buf, trak.bodyStart, trak.end, 'stsz', ['mdia', 'minf', 'stbl']);
    if (!stsz) {
      throw new Error('缺少 stsz box');
    }
    const uniformSize = u32(buf, stsz.bodyStart + 4);
    const sampleCount = u32(buf, stsz.bodyStart + 8);
    const sizes = new Uint32Array(sampleCount);
    if (uniformSize === 0) {
      for (let i = 0; i < sampleCount; i++) {
        sizes[i] = u32(buf, stsz.bodyStart + 12 + i * 4);
      }
    } else {
      sizes.fill(uniformSize);
    }

    // chunk 偏移
    const stco = findRecursive(buf, trak.bodyStart, trak.end, 'stco', ['mdia', 'minf', 'stbl']);
    const co64 = findRecursive(buf, trak.bodyStart, trak.end, 'co64', ['mdia', 'minf', 'stbl']);
    let chunks;
    if (stco) {
      const n = u32(buf, stco.bodyStart + 4);
      chunks = new Float64Array(n);
      for (let i = 0; i < n; i++) {
        chunks[i] = u32(buf, stco.bodyStart + 8 + i * 4);
      }
    } else if (co64) {
      const n = u32(buf, co64.bodyStart + 4);
      chunks = new Float64Array(n);
      for (let i = 0; i < n; i++) {
        chunks[i] = u64(buf, co64.bodyStart + 8 + i * 8);
      }
    } else {
      throw new Error('缺少 stco / co64 box');
    }

    // stsc：每个 chunk 里几个样本
    const stsc = findRecursive(buf, trak.bodyStart, trak.end, 'stsc', ['mdia', 'minf', 'stbl']);
    if (!stsc) {
      throw new Error('缺少 stsc box');
    }
    const stscCount = u32(buf, stsc.bodyStart + 4);
    const stscEntries = [];
    for (let i = 0; i < stscCount; i++) {
      stscEntries.push({
        firstChunk: u32(buf, stsc.bodyStart + 8 + i * 12),
        samplesPerChunk: u32(buf, stsc.bodyStart + 12 + i * 12)
      });
    }

    // 展开成每个样本的文件偏移
    const offsets = new Float64Array(sampleCount);
    let si = 0;
    for (let ci = 0; ci < stscEntries.length && si < sampleCount; ci++) {
      const cur = stscEntries[ci];
      const next = stscEntries[ci + 1];
      const lastChunk = next ? next.firstChunk - 2 : chunks.length - 1;
      for (let c = cur.firstChunk - 1; c <= lastChunk && si < sampleCount; c++) {
        if (c >= chunks.length) {
          break;
        }
        let off = chunks[c];
        for (let j = 0; j < cur.samplesPerChunk && si < sampleCount; j++) {
          offsets[si] = off;
          off += sizes[si];
          si++;
        }
      }
    }
    if (si < sampleCount) {
      // 补齐（stsc 覆盖不全时按最后一个 chunk 续排）
      let off = si > 0 ? offsets[si - 1] + sizes[si - 1] : 0;
      for (; si < sampleCount; si++) {
        offsets[si] = off;
        off += sizes[si];
      }
    }

    // stts：每个样本的时长
    const stts = findRecursive(buf, trak.bodyStart, trak.end, 'stts', ['mdia', 'minf', 'stbl']);
    const durations = new Uint32Array(sampleCount);
    if (stts) {
      const n = u32(buf, stts.bodyStart + 4);
      let idx = 0;
      for (let i = 0; i < n && idx < sampleCount; i++) {
        const cnt = u32(buf, stts.bodyStart + 8 + i * 8);
        const delta = u32(buf, stts.bodyStart + 12 + i * 8);
        for (let j = 0; j < cnt && idx < sampleCount; j++) {
          durations[idx++] = delta;
        }
      }
      while (idx < sampleCount) {
        durations[idx++] = durations[idx - 1] || 40;
      }
    } else {
      durations.fill(40);
    }

    // stss：关键帧样本号（1-based）
    const stss = findRecursive(buf, trak.bodyStart, trak.end, 'stss', ['mdia', 'minf', 'stbl']);
    const syncSet = new Set();
    if (stss) {
      const n = u32(buf, stss.bodyStart + 4);
      for (let i = 0; i < n; i++) {
        syncSet.add(u32(buf, stss.bodyStart + 8 + i * 4));
      }
    } else {
      // 没有 stss：全当关键帧（每个样本独立可解）
      for (let i = 0; i < sampleCount; i++) {
        syncSet.add(i + 1);
      }
    }

    this.samples = [];
    let dts = 0;
    for (let i = 0; i < sampleCount; i++) {
      this.samples.push({
        index: i,
        offset: offsets[i],
        size: sizes[i],
        duration: durations[i],
        dts,
        sync: syncSet.has(i + 1)
      });
      dts += durations[i];
    }
    this.duration = dts / this.timescale;
    this.sampleCount = sampleCount;
  }

  /**
   * 按 GOP 分组：每个 GOP 从一个关键帧开始，到下一个关键帧之前结束。
   *
   * @returns {Array<{start:number,end:number,samples:Array<object>,startTime:number,duration:number}>}
   */
  gops() {
    const out = [];
    let cur = null;
    for (const s of this.samples) {
      if (s.sync || !cur) {
        if (cur) {
          out.push(cur);
        }
        cur = { start: s.index, end: s.index + 1, samples: [s], startTime: s.dts, duration: 0 };
      } else {
        cur.samples.push(s);
        cur.end = s.index + 1;
      }
      cur.duration += s.duration;
    }
    if (cur) {
      out.push(cur);
    }
    return out;
  }

  /**
   * 构造 init segment（{@code ftyp + moov}）。
   *
   * <p>moov 直接复用源文件的（{@code hvcC}/{@code avcC} 等编码参数原样保留），
   * 只做三件事：丢掉音频轨、清空 {@code stco}（分片模式下不用）、补上
   * {@code mvex/trex}（告诉解复用器「这是分片 MP4」）。
   *
   * @returns {Uint8Array}
   */
  initSegment() {
    if (this._init) {
      return this._init;
    }
    const buf = this.buf;
    const moov = this.moovBox;
    const parts = [];
    for (const c of parseBoxes(buf, moov.bodyStart, moov.end)) {
      if (c.type === 'trak') {
        if (!this._isVideoTrak(c)) {
          continue;
        }
        parts.push(this._patchTrack(c));
      } else if (c.type === 'mvex' || c.type === 'udta') {
        continue;
      } else {
        parts.push(buf.slice(c.start, c.end));
      }
    }
    parts.push(this._buildMvex());
    const moovBytes = makeBox('moov', this._concat(parts));
    const ftyp = this.ftypBox ? buf.slice(this.ftypBox.start, this.ftypBox.end) : makeBox('ftyp', new Uint8Array([0]));
    this._init = this._concat([ftyp, moovBytes]);
    return this._init;
  }

  /** 判断某个 trak 是不是视频轨。 */
  _isVideoTrak(trak) {
    const stsd = findRecursive(this.buf, trak.bodyStart, trak.end, 'stsd', ['mdia', 'minf', 'stbl']);
    if (!stsd) {
      return false;
    }
    const entries = parseBoxes(this.buf, stsd.bodyStart + 8, stsd.end);
    return entries.some(e => {
      const fourcc = String.fromCharCode(this.buf[e.start + 4], this.buf[e.start + 5],
        this.buf[e.start + 6], this.buf[e.start + 7]);
      return FOURCC_H264.includes(fourcc) || FOURCC_H265.includes(fourcc);
    });
  }

  /** 重建 trak：丢掉 edts（elst 里的时长/偏移对分片模式没意义）。 */
  _patchTrack(trak) {
    const parts = [];
    for (const c of parseBoxes(this.buf, trak.bodyStart, trak.end)) {
      if (c.type === 'edts') {
        continue;
      }
      parts.push(c.type === 'mdia' ? this._patchMedia(c) : this.buf.slice(c.start, c.end));
    }
    return makeBox('trak', this._concat(parts));
  }

  _patchMedia(mdia) {
    const parts = [];
    for (const c of parseBoxes(this.buf, mdia.bodyStart, mdia.end)) {
      parts.push(c.type === 'minf' ? this._patchMinf(c) : this.buf.slice(c.start, c.end));
    }
    return makeBox('mdia', this._concat(parts));
  }

  _patchMinf(minf) {
    const parts = [];
    for (const c of parseBoxes(this.buf, minf.bodyStart, minf.end)) {
      parts.push(c.type === 'stbl' ? this._patchStbl(c) : this.buf.slice(c.start, c.end));
    }
    return makeBox('minf', this._concat(parts));
  }

  /**
   * 重建 stbl：只留 {@code stsd}（编码参数 hvcC/avcC 在里面），
   * 其余 sample table 全部清空。
   *
   * <p><b>为什么必须清空</b>：分片 MP4 里样本的描述在 {@code moof/trun} 里，
   * moov 中的 stts/stsc/stsz/stco 属于「单文件 MP4」的残余。
   * 留着它们（尤其 stco 被清空但 stsz 还有 1105 项）会让解复用器读到
   * 自相矛盾的索引 —— 实测 ffmpeg 直接报
   * {@code Invalid data found when processing input}。
   *
   * <p>这也是 ffmpeg 自己产出 fMP4 的做法：{@code stts/stsc/stsz/stco} 的
   * count 全为 0，且完全不写 {@code stss}。
   */
  _patchStbl(stbl) {
    const parts = [];
    for (const c of parseBoxes(this.buf, stbl.bodyStart, stbl.end)) {
      if (c.type === 'stsd') {
        parts.push(this.buf.slice(c.start, c.end));
      } else if (c.type === 'stts' || c.type === 'stsc') {
        // version/flags(4) + entry_count(4) = 0
        parts.push(makeBox(c.type, new Uint8Array(8)));
      } else if (c.type === 'stsz') {
        // version/flags(4) + sample_size(4) + sample_count(4) = 0
        parts.push(makeBox('stsz', new Uint8Array(12)));
      } else if (c.type === 'stco' || c.type === 'co64') {
        parts.push(makeBox(c.type, new Uint8Array(8)));
      } else if (c.type === 'stss') {
        // 分片模式不需要 stss：每个 moof 的 trun 里用 sample_flags 标关键帧
        continue;
      } else {
        parts.push(this.buf.slice(c.start, c.end));
      }
    }
    return makeBox('stbl', this._concat(parts));
  }

  /** 构造 {@code mvex > trex}。 */
  _buildMvex() {
    /*
     * trex 的 track_ID 必须和 moov 里 trak 的 track_ID 一致，
     * 否则解复用器报 "could not find corresponding trex (id N)"。
     *
     * payload 是 5 个 uint32 = 20 字节（track_ID、desc_index、duration、size、flags）。
     * 多写一个字节都会让整个 moov 的长度前缀错位。
     */
    const trexPayload = new Uint8Array(20);
    const dv = new DataView(trexPayload.buffer);
    dv.setUint32(0, this.trackId);   // track_ID
    dv.setUint32(4, 1);              // default_sample_description_index
    dv.setUint32(8, 0);              // default_sample_duration
    dv.setUint32(12, 0);             // default_sample_size
    dv.setUint32(16, 0);             // default_sample_flags
    const trex = makeFullBox('trex', 0, 0, trexPayload);
    return makeBox('mvex', trex);
  }

  /**
   * 构造一个分片（{@code moof + mdat}）。
   *
   * @param {object} gop {@link Mp4Remuxer#gops} 里的一项
   * @param {number} sequenceNumber 片段序号
   * @returns {Uint8Array}
   */
  fragment(gop, sequenceNumber = 1) {
    const samples = gop.samples;

    // ---- mdat 的数据 ----
    const total = samples.reduce((s, x) => s + x.size, 0);
    const mdatPayload = new Uint8Array(total);
    let p = 0;
    for (const s of samples) {
      mdatPayload.set(this.buf.subarray(s.offset, s.offset + s.size), p);
      p += s.size;
    }

    // ---- moof ----
    const mfhdPayload = new Uint8Array(4);
    new DataView(mfhdPayload.buffer).setUint32(0, sequenceNumber);
    const mfhd = makeFullBox('mfhd', 0, 0, mfhdPayload);

    // tfhd：flags = 0x020000（default-base-is-moof，data_offset 相对 moof 起点）
    const tfhdPayload = new Uint8Array(4);
    new DataView(tfhdPayload.buffer).setUint32(0, this.trackId);
    const tfhd = makeFullBox('tfhd', 0, 0x020000, tfhdPayload);

    // tfdt：version 1 → baseMediaDecodeTime 是 <b>64 位</b>（高 32 位在前）。
    // 我们的时间都远小于 2^32，所以高 32 位恒为 0，值放在低 32 位。
    // 写成 setUint32(0, startTime) 会把它当高 32 位，得到 startTime × 2^32，
    // 表现为 buffered 区间出现 1.8e11 这种荒谬的时间戳。
    const tfdtPayload = new Uint8Array(8);
    const tfdtView = new DataView(tfdtPayload.buffer);
    tfdtView.setUint32(0, Math.floor(gop.startTime / 4294967296)); // 高 32 位
    tfdtView.setUint32(4, gop.startTime >>> 0);                    // 低 32 位
    const tfdt = makeFullBox('tfdt', 1, 0, tfdtPayload);

    // trun：data-offset + 每个样本的 duration / size / flags
    /*
     * flags 位（ISO/IEC 14496-12 §8.8.8.1）：
     *   0x000001 data-offset-present
     *   0x000004 first-sample-flags-present
     *   0x000100 sample-duration-present   ← 每样本 duration
     *   0x000200 sample-size-present       ← 每样本 size
     *   0x000400 sample-flags-present      ← 每样本 flags
     *   0x000800 sample-composition-time-offsets-present
     *
     * <p>注意 0x000004 是 <b>first-sample-flags</b>，不是 duration ——
     * 写错会让解复用器从错误的偏移开始解析，结果是 appendBuffer 不报错
     * 但 {@code SourceBuffer.buffered} 永远为空（画面全黑、进度条不动）。
     */
    const trunFlags = 0x000001 | 0x000100 | 0x000200 | 0x000400;
    const trunPayload = new Uint8Array(4 /* count */ + 4 /* data_offset */ + samples.length * 12);
    const trunView = new DataView(trunPayload.buffer);
    trunView.setUint32(0, samples.length);
    trunView.setInt32(4, 0); // data_offset：moof 装好后回填
    for (let i = 0; i < samples.length; i++) {
      const s = samples[i];
      const o = 8 + i * 12;
      trunView.setUint32(o, s.duration);
      trunView.setUint32(o + 4, s.size);
      // sample_flags 用 ffmpeg 的取值（实测能播）：
      //   关键帧   = 0x02000000（sample_is_depended_on = 2）
      //   非关键帧 = 0x01010000
      trunView.setUint32(o + 8, s.sync ? 0x02000000 : 0x01010000);
    }
    const trun = makeFullBox('trun', 0, trunFlags, trunPayload);

    const traf = makeBox('traf', this._concat([tfhd, tfdt, trun]));
    const moof = makeBox('moof', this._concat([mfhd, traf]));

    /*
     * 回填 trun 的 data_offset。
     *
     * <p>trun 的字节布局（相对 trun 起点）：
     * <pre>
     *   +0   size(4)
     *   +4   'trun'(4)
     *   +8   version/flags(4)
     *   +12  sample_count(4)
     *   +16  data_offset(4)   ← 要改的就是这里
     * </pre>
     *
     * <p><b>踩过的坑</b>：这个偏移少算过两次 ——
     * 少加 box 头会压掉 fourcc（dump 出来是 `¼`），
     * 少加 4 会压掉 sample_count（变成上百/上千）。
     * 两种情况的共同表现：appendBuffer 不报错，但
     * {@code SourceBuffer.buffered} 永远为空、画面全黑。
     */
    const trunStart = 8 + mfhd.length + 8 + tfhd.length + tfdt.length;
    const dataOffsetPos = trunStart + 8 /* box 头 */ + 4 /* version/flags */ + 4 /* sample_count */;
    const dataOffset = moof.length + 8; // moof 长度 + mdat 头
    new DataView(moof.buffer, moof.byteOffset).setInt32(dataOffsetPos, dataOffset);

    const mdat = makeBox('mdat', mdatPayload);
    return this._concat([moof, mdat]);
  }

  /** 拼接多个 Uint8Array。 */
  _concat(list) {
    let len = 0;
    for (const b of list) {
      len += b.length;
    }
    const out = new Uint8Array(len);
    let o = 0;
    for (const b of list) {
      out.set(b, o);
      o += b.length;
    }
    return out;
  }
}
