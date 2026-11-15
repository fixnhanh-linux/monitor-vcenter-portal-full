// Tạo mã QR (ISO/IEC 18004) ngay trong trình duyệt, không cần thư viện ngoài.
// Chế độ byte (UTF-8), mức sửa lỗi M, tự chọn phiên bản 1–40 và mặt nạ tốt nhất.
// Dùng: qrSvg('otpauth://...', {size: 200})  -> chuỗi <svg>
'use strict';
(function (global) {
  const ECC_CODEWORDS_PER_BLOCK_M = [-1, 10, 16, 26, 18, 24, 16, 18, 22, 22, 26, 30, 22, 22, 24, 24, 28, 28, 26, 26, 26, 26, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28];
  const NUM_EC_BLOCKS_M = [-1, 1, 1, 1, 2, 2, 4, 4, 4, 5, 5, 5, 8, 9, 9, 10, 10, 11, 13, 14, 16, 17, 17, 18, 20, 21, 23, 25, 26, 28, 29, 31, 33, 35, 37, 38, 40, 43, 45, 47, 49];
  const ECL_FORMAT_BITS_M = 0; // M = 00

  function numRawDataModules(ver) {
    let result = (16 * ver + 128) * ver + 64;
    if (ver >= 2) {
      const numAlign = Math.floor(ver / 7) + 2;
      result -= (25 * numAlign - 10) * numAlign - 55;
      if (ver >= 7) result -= 36;
    }
    return result;
  }
  const numDataCodewords = (ver) => Math.floor(numRawDataModules(ver) / 8) - ECC_CODEWORDS_PER_BLOCK_M[ver] * NUM_EC_BLOCKS_M[ver];

  // ---- Reed–Solomon trên GF(2^8), đa thức 0x11D
  function rsMul(x, y) {
    let z = 0;
    for (let i = 7; i >= 0; i--) { z = (z << 1) ^ ((z >>> 7) * 0x11d); z ^= ((y >>> i) & 1) * x; }
    return z & 0xff;
  }
  function rsDivisor(degree) {
    const result = new Array(degree).fill(0); result[degree - 1] = 1;
    let root = 1;
    for (let i = 0; i < degree; i++) {
      for (let j = 0; j < result.length; j++) { result[j] = rsMul(result[j], root); if (j + 1 < result.length) result[j] ^= result[j + 1]; }
      root = rsMul(root, 0x02);
    }
    return result;
  }
  function rsRemainder(data, divisor) {
    const result = divisor.map(() => 0);
    for (const b of data) {
      const factor = b ^ result.shift(); result.push(0);
      divisor.forEach((coef, i) => { result[i] ^= rsMul(coef, factor); });
    }
    return result;
  }

  function addEccAndInterleave(data, ver) {
    const numBlocks = NUM_EC_BLOCKS_M[ver], blockEccLen = ECC_CODEWORDS_PER_BLOCK_M[ver];
    const rawCodewords = Math.floor(numRawDataModules(ver) / 8);
    const numShortBlocks = numBlocks - (rawCodewords % numBlocks);
    const shortBlockLen = Math.floor(rawCodewords / numBlocks);
    const blocks = [], div = rsDivisor(blockEccLen);
    for (let i = 0, k = 0; i < numBlocks; i++) {
      const dat = data.slice(k, k + shortBlockLen - blockEccLen + (i < numShortBlocks ? 0 : 1));
      k += dat.length;
      const ecc = rsRemainder(dat, div);
      if (i < numShortBlocks) dat.push(0);
      blocks.push(dat.concat(ecc));
    }
    const result = [];
    for (let i = 0; i < blocks[0].length; i++) {
      blocks.forEach((block, j) => { if (i !== shortBlockLen - blockEccLen || j >= numShortBlocks) result.push(block[i]); });
    }
    return result;
  }

  function encode(text) {
    const bytes = Array.from(new TextEncoder().encode(text));
    let ver, bits;
    for (ver = 1; ver <= 40; ver++) {
      const ccBits = ver < 10 ? 8 : 16;
      const capacity = numDataCodewords(ver) * 8;
      if (4 + ccBits + bytes.length * 8 <= capacity) break;
    }
    if (ver > 40) throw new Error('Dữ liệu quá dài cho mã QR');
    bits = [];
    const push = (val, len) => { for (let i = len - 1; i >= 0; i--) bits.push((val >>> i) & 1); };
    push(0x4, 4); push(bytes.length, ver < 10 ? 8 : 16);
    bytes.forEach((b) => push(b, 8));
    const capBits = numDataCodewords(ver) * 8;
    push(0, Math.min(4, capBits - bits.length));
    push(0, (8 - (bits.length % 8)) % 8);
    for (let pad = 0xec; bits.length < capBits; pad ^= 0xec ^ 0x11) push(pad, 8);
    const data = [];
    for (let i = 0; i < bits.length; i += 8) data.push(parseInt(bits.slice(i, i + 8).join(''), 2));
    return { ver, codewords: addEccAndInterleave(data, ver) };
  }

  function build(text) {
    const { ver, codewords } = encode(text);
    const size = ver * 4 + 17;
    const mod = Array.from({ length: size }, () => new Array(size).fill(false));
    const fn = Array.from({ length: size }, () => new Array(size).fill(false));
    const set = (x, y, dark) => { mod[y][x] = dark; fn[y][x] = true; };

    // mẫu định thời
    for (let i = 0; i < size; i++) { set(6, i, i % 2 === 0); set(i, 6, i % 2 === 0); }
    // mẫu định vị 3 góc
    const finder = (cx, cy) => {
      for (let dy = -4; dy <= 4; dy++) for (let dx = -4; dx <= 4; dx++) {
        const d = Math.max(Math.abs(dx), Math.abs(dy)), x = cx + dx, y = cy + dy;
        if (x >= 0 && x < size && y >= 0 && y < size) set(x, y, d !== 2 && d !== 4);
      }
    };
    finder(3, 3); finder(size - 4, 3); finder(3, size - 4);
    // mẫu căn chỉnh
    const alignPos = (() => {
      if (ver === 1) return [];
      const numAlign = Math.floor(ver / 7) + 2;
      const step = ver === 32 ? 26 : Math.ceil((ver * 4 + 4) / (numAlign * 2 - 2)) * 2;
      const res = [6];
      for (let pos = size - 7; res.length < numAlign; pos -= step) res.splice(1, 0, pos);
      return res;
    })();
    alignPos.forEach((ax, i) => alignPos.forEach((ay, j) => {
      if ((i === 0 && j === 0) || (i === 0 && j === alignPos.length - 1) || (i === alignPos.length - 1 && j === 0)) return;
      for (let dy = -2; dy <= 2; dy++) for (let dx = -2; dx <= 2; dx++) set(ax + dx, ay + dy, Math.max(Math.abs(dx), Math.abs(dy)) !== 1);
    }));
    // giữ chỗ vùng định dạng + phiên bản
    const drawFormat = (mask) => {
      const data = (ECL_FORMAT_BITS_M << 3) | mask;
      let rem = data;
      for (let i = 0; i < 10; i++) rem = (rem << 1) ^ ((rem >>> 9) * 0x537);
      const b = ((data << 10) | rem) ^ 0x5412;
      const bit = (i) => ((b >>> i) & 1) !== 0;
      for (let i = 0; i <= 5; i++) set(8, i, bit(i));
      set(8, 7, bit(6)); set(8, 8, bit(7)); set(7, 8, bit(8));
      for (let i = 9; i < 15; i++) set(14 - i, 8, bit(i));
      for (let i = 0; i < 8; i++) set(size - 1 - i, 8, bit(i));
      for (let i = 8; i < 15; i++) set(8, size - 15 + i, bit(i));
      set(8, size - 8, true);
    };
    drawFormat(0);
    if (ver >= 7) {
      let rem = ver;
      for (let i = 0; i < 12; i++) rem = (rem << 1) ^ ((rem >>> 11) * 0x1f25);
      const b = (ver << 12) | rem;
      for (let i = 0; i < 18; i++) {
        const bit = ((b >>> i) & 1) !== 0, a = size - 11 + (i % 3), c = Math.floor(i / 3);
        set(a, c, bit); set(c, a, bit);
      }
    }
    // đặt dữ liệu theo đường zig-zag
    let i = 0;
    for (let right = size - 1; right >= 1; right -= 2) {
      if (right === 6) right = 5;
      for (let vert = 0; vert < size; vert++) {
        for (let j = 0; j < 2; j++) {
          const x = right - j, upward = ((right + 1) & 2) === 0, y = upward ? size - 1 - vert : vert;
          if (!fn[y][x] && i < codewords.length * 8) { mod[y][x] = ((codewords[i >>> 3] >>> (7 - (i & 7))) & 1) !== 0; i++; }
        }
      }
    }
    // chọn mặt nạ có điểm phạt thấp nhất
    const MASKS = [(x, y) => (x + y) % 2 === 0, (x, y) => y % 2 === 0, (x) => x % 3 === 0, (x, y) => (x + y) % 3 === 0,
      (x, y) => (Math.floor(x / 3) + Math.floor(y / 2)) % 2 === 0, (x, y) => ((x * y) % 2) + ((x * y) % 3) === 0,
      (x, y) => (((x * y) % 2) + ((x * y) % 3)) % 2 === 0, (x, y) => (((x + y) % 2) + ((x * y) % 3)) % 2 === 0];
    const applyMask = (m) => { for (let y = 0; y < size; y++) for (let x = 0; x < size; x++) if (!fn[y][x] && MASKS[m](x, y)) mod[y][x] = !mod[y][x]; };
    const penalty = () => {
      let score = 0;
      const runs = (get) => {
        for (let a = 0; a < size; a++) {
          let color = null, run = 0; const hist = [];
          for (let b = 0; b < size; b++) {
            const c = get(a, b);
            if (c === color) { run++; if (run === 5) score += 3; else if (run > 5) score++; }
            else { hist.push(run); color = c; run = 1; }
          }
          hist.push(run);
          const line = Array.from({ length: size }, (_, b) => (get(a, b) ? 1 : 0)).join('');
          let k = -1; while ((k = line.indexOf('1011101', k + 1)) !== -1) {
            if (line.slice(Math.max(0, k - 4), k) === '0000' || k - 4 < 0 || line.slice(k + 7, k + 11) === '0000' || k + 11 > size) score += 40;
          }
        }
      };
      runs((a, b) => mod[a][b]); runs((a, b) => mod[b][a]);
      for (let y = 0; y < size - 1; y++) for (let x = 0; x < size - 1; x++) {
        const c = mod[y][x];
        if (c === mod[y][x + 1] && c === mod[y + 1][x] && c === mod[y + 1][x + 1]) score += 3;
      }
      let dark = 0; mod.forEach((row) => row.forEach((c) => { if (c) dark++; }));
      const total = size * size;
      score += (Math.ceil(Math.abs(dark * 20 - total * 10) / total) - 1) * 10;
      return score;
    };
    let best = 0, bestScore = Infinity;
    for (let m = 0; m < 8; m++) {
      applyMask(m); drawFormat(m);
      const sc = penalty();
      if (sc < bestScore) { bestScore = sc; best = m; }
      applyMask(m); // bỏ mặt nạ (XOR lần 2)
    }
    applyMask(best); drawFormat(best);
    return mod;
  }

  function qrSvg(text, { size = 200, margin = 4, dark = '#000', light = '#fff' } = {}) {
    const m = build(text), n = m.length, total = n + margin * 2;
    let d = '';
    for (let y = 0; y < n; y++) for (let x = 0; x < n; x++) if (m[y][x]) d += `M${x + margin},${y + margin}h1v1h-1z`;
    return `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 ${total} ${total}" width="${size}" height="${size}" shape-rendering="crispEdges" role="img" aria-label="Mã QR">`
      + `<rect width="100%" height="100%" fill="${light}"/><path d="${d}" fill="${dark}"/></svg>`;
  }
  global.qrSvg = qrSvg;
  global.qrMatrix = build;
})(typeof window !== 'undefined' ? window : globalThis);
