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

/* ==================== HEVC 参数集（VPS/SPS/PPS） ==================== */

/** HEVC NAL 单元类型：视频参数集。 */
const NAL_VPS = 32;
/** HEVC NAL 单元类型：序列参数集。 */
const NAL_SPS = 33;
/** HEVC NAL 单元类型：图像参数集。 */
const NAL_PPS = 34;

/**
 * 各参数集的合理长度上限（字节）。
 *
 * <p>真正的参数集都很小：VPS 约 20~30 字节，SPS 通常 30~60（4K 也就百来字节），
 * PPS 不到 20。实测正常录像：{@code VPS 23 / SPS 34 / PPS 7}。
 * 上限设得宽松（VPS 128 / SPS 512 / PPS 256）—— 目的只是拦住"整帧被当成
 * 参数集写进来"这种量级错误（实测见过 3133 字节的 VPS），不是严格校验。
 */
const PARAM_MAX_LEN = { [NAL_VPS]: 128, [NAL_SPS]: 512, [NAL_PPS]: 256 };

/** 参数集最短长度（含 2 字节 NAL 头）。 */
const PARAM_MIN_LEN = 4;

/**
 * 校验一个 HEVC 参数集。
 *
 * <p>两个判据，都是"必然坏"的硬伤：
 * <ol>
 *   <li>{@code forbidden_zero_bit} 必须为 0（NAL 首字节最高位）。
 *       实测 ZLM 的坏 hvcC 里 VPS 首字节是 {@code 0xC0}（正常应为 {@code 0x40}），
 *       最高位置着 —— 说明那段字节根本不是从 NAL 边界开始的；</li>
 *   <li>NAL 头里的 type 必须和它所在的数组一致，且长度在合理范围。</li>
 * </ol>
 *
 * @param {number} expectedType 期望的 NAL 类型（32/33/34）
 * @param {Uint8Array} nal
 * @returns {boolean}
 */
export function isValidParamSet(expectedType, nal) {
  if (!nal || nal.length < PARAM_MIN_LEN || nal.length > PARAM_MAX_LEN[expectedType]) {
    return false;
  }
  if (nal[0] & 0x80) {
    // forbidden_zero_bit 置位
    return false;
  }
  const type = (nal[0] >> 1) & 0x3F;
  if (type !== expectedType) {
    return false;
  }
  // nuh_temporal_id_plus1 不能为 0（HEVC 规范硬性要求）
  return (nal[1] & 0x07) !== 0;
}

/**
 * 从样本字节里捞出可用的参数集。
 *
 * <p>MP4 里的样本是「长度前缀 NAL」格式（每段 4 字节大端长度 + NAL）。
 * 编码器通常会在每个 IDR 前重复 VPS/SPS/PPS，所以即使 hvcC 写坏了，
 * 真正的参数集往往仍躺在 mdat 里 —— 实测某段坏录像的 hvcC 是一个
 * 3133 字节的垃圾 VPS，但第 38 个样本起每个关键帧都带着正确的 23 字节 VPS。
 *
 * @param {Uint8Array} sample 一个样本的字节
 * @param {Set<number>} needed 还需要哪些类型
 * @returns {Object<number, Uint8Array>} 找到的参数集
 */
export function scanParamSets(sample, needed) {
  const found = {};
  let off = 0;
  while (off + 4 <= sample.length) {
    const len = ((sample[off] << 24) | (sample[off + 1] << 16) |
      (sample[off + 2] << 8) | sample[off + 3]) >>> 0;
    if (len < 2 || off + 4 + len > sample.length) {
      break;
    }
    const start = off + 4;
    const type = (sample[start] >> 1) & 0x3F;
    if (needed.has(type) && !found[type]) {
      const nal = sample.subarray(start, start + len);
      if (isValidParamSet(type, nal)) {
        found[type] = nal.slice();
      }
    }
    off = start + len;
  }
  return found;
}

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
 * 探测 moov 时，首次取文件尾的字节数。
 *
 * <p>ZLM 录出的 MP4 是 {@code ftyp + free + mdat + moov [+ free]}，索引在文件尾。
 *
 * <p><b>起始取 64KB</b>：实测 moov 基本都在尾部 64KB 内（16MB 录像的 moov
 * 约 24KB）。公网带宽只有 ~80KB/s 时，256KB 要 3.2 秒、64KB 只要 0.8 秒。
 * 万一不够，{@link doProbeCodec} 会按 ×4 渐进放大（64KB → 256KB → 1MB…），
 * 只是多一次往返，不会失败。
 */
const MOOV_PROBE_BYTES = 64 * 1024

/** 探测 moov 时单次最多放大到的字节数。 */
const MOOV_MAX_BYTES = 32 * 1024 * 1024

/** 探测结果缓存：同一 URL 只探一次（避免重复点击/重渲染时重复下载）。 */
const codecCache = new Map()

/**
 * moov 缓存：url → {@code {bytes, offset}}。
 *
 * <p>探测编码时已经把 moov 读下来了，容错通路又要读一次 ——
 * 公网带宽只有 ~80KB/s，重复读 64KB 就是白等 0.8 秒。
 * 这里把读到的 moov 留下来给容错通路复用。
 */
const moovCache = new Map()

/**
 * 取出已缓存的 moov（没有则返回 null）。
 *
 * @param {string} url 录像地址
 * @returns {{bytes: Uint8Array, offset: number}|null}
 */
export function getCachedMoov(url) {
  return moovCache.get(url) || null
}

/**
 * 只下载「文件尾的 moov」来探测视频编码，不下载整个文件。
 *
 * <p><b>为什么要先探测</b>：H.265 录像里夹着坏帧的概率很高（UDP 丢包），
 * 原生 {@code <video src>} 播不了；而浏览器一旦拿到 {@code src} 就会
 * <b>预取整个文件</b>（实测 57MB 全下完才报错），白白浪费带宽。
 * 先花一次几百 KB 的 Range 请求问清编码，H.265 直接走 MSE 通路就能省掉这次浪费。
 *
 * @param {string} url 录像地址（可带 query）
 * @returns {Promise<string>} {@link CODEC_H264} / {@link CODEC_H265} / {@link CODEC_UNKNOWN}
 */
export async function probeCodecFromUrl(url) {
  if (codecCache.has(url)) {
    return codecCache.get(url)
  }
  const r = await doProbeCodec(url)
  if (!r) {
    return CODEC_UNKNOWN
  }
  // 只缓存「探到了」的结果；unknown 可能是临时网络问题，下次再试
  codecCache.set(url, r.codec)
  cacheMoov(url, r)
  return r.codec
}

/**
 * 缓存 moov，原始 URL 与规范化后的 URL 都存一份。
 *
 * <p>调用方传的可能是相对路径（{@code /record/...}），而
 * {@link MseRecordPlayer} 内部会先 {@code new URL(url, location.href)}
 * 规范化 —— 两者字符串不一定相同，两个 key 都存才命中得上。
 *
 * @param {string} url 原始 URL
 * @param {{bytes:Uint8Array,offset:number}} r moov 数据
 */
function cacheMoov(url, r) {
  const entry = { bytes: r.bytes, offset: r.offset }
  moovCache.set(url, entry)
  try {
    moovCache.set(new URL(url, location.href).toString(), entry)
  } catch (e) {
    // URL 非法就算了，只留原始 key
  }
}

/**
 * 实际执行探测。
 *
 * @param {string} url 录像地址
 * @returns {Promise<{bytes:Uint8Array,offset:number,codec:string}|null>}
 */
async function doProbeCodec(url) {
  try {
    const head = await fetch(url, { method: 'HEAD' })
    if (!head.ok) {
      return null
    }
    const size = Number(head.headers.get('Content-Length') || 0)
    if (!size) {
      return null
    }
    let take = Math.min(MOOV_PROBE_BYTES, size)
    for (;;) {
      const start = size - take
      const resp = await fetch(url, { headers: { Range: `bytes=${start}-${size - 1}` } })
      if (!resp.ok) {
        return CODEC_UNKNOWN
      }
      const bytes = new Uint8Array(await resp.arrayBuffer())
      /*
       * 在尾部字节里<b>搜索</b> 'moov' 四字符，不能用 parseBoxes。
       *
       * <p>尾部字节的开头落在 mdat 的中间，不是 box 边界 ——
       * parseBoxes 从 offset 0 解析会读到垃圾长度，永远找不到 moov
       * （表现为翻倍重试到 32MB，比整文件下载还费）。
       */
      const hit = findBoxByType(bytes, 'moov')
      if (hit) {
        const moovBytes = bytes.subarray(hit.start, hit.start + hit.size)
        const r = new Mp4Remuxer(moovBytes)
        return { bytes: moovBytes, offset: start + hit.start, codec: r.codec }
      }
      if (take >= size || take >= MOOV_MAX_BYTES) {
        return null
      }
      take = Math.min(take * 4, size, MOOV_MAX_BYTES)
    }
  } catch (e) {
    return null
  }
}

/**
 * 在字节数组里按四字符类型搜索 box。
 *
 * @param {Uint8Array} buf 字节数组
 * @param {string} type 四字符类型
 * @returns {{start:number,size:number}|null} box 起始偏移与长度
 */
function findBoxByType(buf, type) {
  const c0 = type.charCodeAt(0)
  const c1 = type.charCodeAt(1)
  const c2 = type.charCodeAt(2)
  const c3 = type.charCodeAt(3)
  for (let i = 4; i + 4 <= buf.length; i++) {
    if (buf[i] === c0 && buf[i + 1] === c1 && buf[i + 2] === c2 && buf[i + 3] === c3) {
      const start = i - 4
      const size = ((buf[start] << 24) | (buf[start + 1] << 16)
        | (buf[start + 2] << 8) | buf[start + 3]) >>> 0
      // 长度前缀必须合理（至少 8，且不超出数组）
      if (size >= 8 && start + size <= buf.length) {
        return { start, size }
      }
    }
  }
  return null
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
   * @param {Uint8Array} buf       至少包含 {@code moov} 的字节块（也可以是整个文件）
   * @param {number} [baseOffset]  该字节块在<b>文件</b>里的起始偏移。
   *   只传「文件尾部的 moov」时用它把 box 位置换算回文件绝对偏移。
   *   sample 的 {@code stco} 记录本来就是文件绝对偏移，所以解析索引时不需要它；
   *   它只用于判定「哪部分数据已经下载好了」。
   */
  constructor(buf, baseOffset = 0) {
    this.buf = buf;
    this.baseOffset = baseOffset;
    this.boxes = parseBoxes(buf, 0, buf.length);
    this.ftypBox = this.boxes.find(b => b.type === 'ftyp') || null;
    this.moovBox = this.boxes.find(b => b.type === 'moov') || null;
    if (!this.moovBox) {
      throw new Error('找不到 moov box（不是有效的 MP4，或录像仍在录制中）');
    }
    /** 校验通过的编码参数集：HEVC NAL 类型 → 字节。 */
    this.paramSets = {};
    /** 缺失或校验不过的参数集类型（空表示 hvcC 完好）。 */
    this.badParamTypes = [];
    /** hvcC box 的位置（重写参数集时用）。 */
    this.hvcCBox = null;
    /** 是否已经用码流里捞到的参数集修补过 hvcC。 */
    this._paramPatched = false;
    this._parseVideoTrack();
  }

  /**
   * 解析并校验视频轨的编码参数集（仅 HEVC）。
   *
   * <p>记录三样东西：
   * <ul>
   *   <li>{@code paramSets} —— 校验通过的参数集（type → 字节）；</li>
   *   <li>{@code badParamTypes} —— 缺失或校验不过的类型；</li>
   *   <li>{@code hvcCBox} —— hvcC box 在缓冲区里的位置，供重写用。</li>
   * </ul>
   *
   * <p>H.264 的 {@code avcC} 结构不同（SPS/PPS 没有 array 包裹），暂不做同样处理。
   *
   * @param {object} stsd stsd box
   */
  _parseParamSets(stsd) {
    this.paramSets = {};
    this.badParamTypes = [];
    this.hvcCBox = null;
    if (!FOURCC_H265.includes(this.fourcc)) {
      return;
    }
    for (const entry of parseBoxes(this.buf, stsd.bodyStart + 8, stsd.end)) {
      // 视觉 sample entry 的固定字段 78 字节，之后才是子 box
      const hvcC = parseBoxes(this.buf, entry.bodyStart + 78, entry.end).find(c => c.type === 'hvcC');
      if (!hvcC) {
        continue;
      }
      this.hvcCBox = hvcC;
      const p = this.buf.subarray(hvcC.bodyStart, hvcC.end);
      // 前 22 字节是配置字段，[22] 是 numOfArrays
      if (p.length < 23) {
        this.badParamTypes = [NAL_VPS, NAL_SPS, NAL_PPS];
        return;
      }
      let q = 23;
      for (let i = 0; i < p[22] && q + 3 <= p.length; i++) {
        const type = p[q] & 0x3F;
        const count = (p[q + 1] << 8) | p[q + 2];
        q += 3;
        for (let j = 0; j < count && q + 2 <= p.length; j++) {
          const len = (p[q] << 8) | p[q + 1];
          q += 2;
          if (q + len > p.length) {
            break;
          }
          const nal = p.subarray(q, q + len);
          if ((type === NAL_VPS || type === NAL_SPS || type === NAL_PPS) && isValidParamSet(type, nal)) {
            this.paramSets[type] = nal.slice();
          }
          q += len;
        }
      }
      for (const t of [NAL_VPS, NAL_SPS, NAL_PPS]) {
        if (!this.paramSets[t]) {
          this.badParamTypes.push(t);
        }
      }
      return;
    }
  }

  /**
   * 还需要从码流里补哪些参数集。
   *
   * @returns {number[]} HEVC NAL 类型数组（空数组表示 hvcC 完好）
   */
  get needsParamRecovery() {
    return this.badParamTypes ? this.badParamTypes.slice() : [];
  }

  /**
   * 用从码流里捞回来的参数集修补 hvcC。
   *
   * <p>只接受校验通过、且确实缺失的那几类，避免把别处的参数集乱塞进来
   * （比如把 VPS 塞进 SPS 的位置）。
   *
   * @param {Object<number, Uint8Array>} sets type → 字节
   * @returns {boolean} 是否发生了修补
   */
  setParamSets(sets) {
    let patched = false;
    for (const t of [NAL_VPS, NAL_SPS, NAL_PPS]) {
      const nal = sets && sets[t];
      if (!nal || !isValidParamSet(t, nal)) {
        continue;
      }
      if (this.badParamTypes.includes(t)) {
        this.paramSets[t] = nal.slice();
        this.badParamTypes = this.badParamTypes.filter(x => x !== t);
        patched = true;
      }
    }
    if (patched) {
      this._paramPatched = true;
      // initSegment 有缓存，参数变了必须让它重建
      this._init = null;
    }
    return patched;
  }

  /**
   * 按当前的 {@code paramSets} 重写 hvcC 的 payload。
   *
   * <p>hvcC 的布局：前 22 字节是通用配置（profile/tier/level、色度格式、
   * 位深等），第 23 字节是 {@code numOfArrays}，之后每段是
   * {@code 1 字节 array_completeness|type + 2 字节 numNalus + 若干 [2 字节长度 + NAL]}。
   *
   * @returns {Uint8Array}
   */
  _buildHvcCPayload() {
    const head = this.buf.subarray(this.hvcCBox.bodyStart, this.hvcCBox.bodyStart + 22);
    const chunks = [head];
    const types = [NAL_VPS, NAL_SPS, NAL_PPS].filter(t => this.paramSets[t]);
    chunks.push(new Uint8Array([types.length]));
    for (const t of types) {
      const nal = this.paramSets[t];
      const ent = new Uint8Array(5 + nal.length);
      ent[0] = 0x80 | t;                 // array_completeness = 1
      ent[1] = 0; ent[2] = 1;            // numNalus = 1
      ent[3] = (nal.length >> 8) & 0xff;
      ent[4] = nal.length & 0xff;
      ent.set(nal, 5);
      chunks.push(ent);
    }
    return this._concat(chunks);
  }

  /**
   * 重建 stsd（仅在参数集被修补过时才需要）。
   *
   * @param {object} stsd stsd box
   * @returns {Uint8Array}
   */
  _patchStsd(stsd) {
    const head = this.buf.slice(stsd.bodyStart, stsd.bodyStart + 8); // version/flags + entry_count
    const entries = [];
    for (const entry of parseBoxes(this.buf, stsd.bodyStart + 8, stsd.end)) {
      const children = [];
      for (const c of parseBoxes(this.buf, entry.bodyStart + 78, entry.end)) {
        if (c.type === 'hvcC') {
          children.push(makeBox('hvcC', this._buildHvcCPayload()));
        } else {
          children.push(this.buf.slice(c.start, c.end));
        }
      }
      const fixed = this.buf.slice(entry.bodyStart, entry.bodyStart + 78);
      entries.push(makeBox(
        String.fromCharCode(this.buf[entry.start + 4], this.buf[entry.start + 5],
          this.buf[entry.start + 6], this.buf[entry.start + 7]),
        this._concat([fixed, ...children])
      ));
    }
    return makeBox('stsd', this._concat([head, ...entries]));
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

    // 编码参数（hvcC）校验：坏的话先记下来，等播放器从 mdat 里捞回真的
    this._parseParamSets(stsd);

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
   * <p>每个 GOP 额外带上 {@code dataStart}/{@code dataEnd}（<b>文件绝对偏移</b>），
   * 调用方据此只下载这一段就能播这个 GOP —— 不用把整个录像拉下来。
   *
   * @returns {Array<{start:number,end:number,samples:Array<object>,startTime:number,duration:number,dataStart:number,dataEnd:number}>}
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
    for (const g of out) {
      g.dataStart = g.samples[0].offset;
      const last = g.samples[g.samples.length - 1];
      g.dataEnd = last.offset + last.size;
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
    /*
     * ftyp 只在「整个文件在手」时能从源文件抄；
     * 只下载文件尾部 moov 的场景下 ftyp 不在手上，这里给一个标准头
     * （major_brand=isom, minor_version=0x200, compatible=isomiso2mp41）。
     */
    const ftyp = this.ftypBox
      ? buf.slice(this.ftypBox.start, this.ftypBox.end)
      : makeBox('ftyp', new Uint8Array([
        0x69, 0x73, 0x6f, 0x6d,             // 'isom'
        0x00, 0x00, 0x02, 0x00,             // minor_version
        0x69, 0x73, 0x6f, 0x6d, 0x69, 0x73, 0x6f, 0x32,
        0x6d, 0x70, 0x34, 0x31              // 'isomiso2mp41'
      ]));
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
        // 参数集被修补过就重建 stsd（hvcC 换了内容），否则原样拷贝
        parts.push(this._paramPatched ? this._patchStsd(c) : this.buf.slice(c.start, c.end));
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
   * @param {object} gop           {@link Mp4Remuxer#gops} 里的一项
   * @param {number} sequenceNumber 片段序号
   * @param {Uint8Array} [data]    该 GOP 的样本字节（从 {@code gop.dataStart} 开始）。
   *   不传时从构造函数的 {@code buf} 里按绝对偏移取（整个文件在手的场景）。
   * @returns {Uint8Array}
   */
  fragment(gop, sequenceNumber = 1, data = null) {
    const samples = gop.samples;

    // ---- mdat 的数据 ----
    const total = samples.reduce((s, x) => s + x.size, 0);
    const mdatPayload = new Uint8Array(total);
    let p = 0;
    for (const s of samples) {
      if (data) {
        // data 是从 gop.dataStart 开始的连续字节，样本偏移要减去它
        const from = s.offset - gop.dataStart;
        mdatPayload.set(data.subarray(from, from + s.size), p);
      } else {
        mdatPayload.set(this.buf.subarray(s.offset, s.offset + s.size), p);
      }
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
