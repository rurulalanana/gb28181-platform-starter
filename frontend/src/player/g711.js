/**
 * G.711 音频解码（A-law / μ-law）。
 *
 * <p>国标设备（GB/T 28181）几乎都用 G.711 推音频，ZLM 转成 FLV 后音频编码
 * 仍是 PCMA（A-law）或 PCMU（μ-law）。MSE（Media Source Extensions）只认
 * AAC/MP3/Opus，遇到 G.711 会直接抛 {@code CodecUnsupported}，所以音频必须
 * 在 JS 里自己解码，交给 Web Audio 播放。
 *
 * <p>G.711 每个采样 8 bit、采样率固定 8000 Hz，解码就是「查表 + 归一化」，
 * 纯 JS 完全够快（8 kHz 单声道约 8 KB/s），不需要 WASM。
 *
 * <p>算法取自 ITU-T G.711 标准参考实现，与 ffmpeg 的 {@code pcm_alaw} /
 * {@code pcm_mulaw} 输出一致。
 */

/**
 * A-law 单采样解码：8 bit 编码值 → 16 bit 线性 PCM。
 *
 * @param {number} aVal 0~255 的 A-law 编码字节
 * @returns {number} -32768~32767 的线性 PCM
 */
function alawToLinear(aVal) {
  // 标准规定先与 0x55 异或，还原被翻转的符号位与段内位
  let v = aVal ^ 0x55
  // 段内 4 bit
  let t = (v & 0x0f) << 4
  // 段落号 3 bit
  const seg = (v & 0x70) >> 4
  if (seg === 0) {
    t += 8
  } else if (seg === 1) {
    t += 0x108
  } else {
    t += 0x108
    t <<= seg - 1
  }
  return v & 0x80 ? t : -t
}

/**
 * μ-law 单采样解码：8 bit 编码值 → 16 bit 线性 PCM。
 *
 * @param {number} uVal 0~255 的 μ-law 编码字节
 * @returns {number} -32768~32767 的线性 PCM
 */
function mulawToLinear(uVal) {
  // μ-law 先取反，再拆段落与段内位
  const v = ~uVal & 0xff
  let t = ((v & 0x0f) << 3) + 0x84
  t <<= (v & 0x70) >> 4
  return v & 0x80 ? 0x84 - t : t - 0x84
}

/**
 * 预生成的解码表：下标是 8 bit 编码字节，值是归一化到 [-1, 1] 的浮点采样。
 *
 * <p>预生成而不用逐样本算：8 kHz 单声道一小时约 2900 万个采样，
 * 每次播放都重算会是纯浪费。
 */
const ALAW_TABLE = (() => {
  const t = new Float32Array(256)
  for (let i = 0; i < 256; i++) t[i] = alawToLinear(i) / 32768
  return t
})()

const MULAW_TABLE = (() => {
  const t = new Float32Array(256)
  for (let i = 0; i < 256; i++) t[i] = mulawToLinear(i) / 32768
  return t
})()

/**
 * 把一段 G.711 字节流解码成浮点 PCM。
 *
 * @param {Uint8Array} data 一个 FLV 音频标签里的裸 G.711 数据
 * @param {'alaw'|'mulaw'} law 律制
 * @returns {Float32Array} 与 {@code data} 等长的归一化 PCM
 */
export function decodeG711(data, law) {
  const table = law === 'mulaw' ? MULAW_TABLE : ALAW_TABLE
  const out = new Float32Array(data.length)
  for (let i = 0; i < data.length; i++) out[i] = table[data[i]]
  return out
}

/** G.711 的固定采样率（Hz），标准规定 8000，FLV 头里虽然也有但要防脏数据。 */
export const G711_SAMPLE_RATE = 8000
