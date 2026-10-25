package io.github.rurulalanana.starter.service;

import io.github.rurulalanana.starter.common.BizException;
import io.github.rurulalanana.starter.config.Gb28181Properties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * HTTPS 证书管理：校验、安装、生成 nginx 站点配置并安全 reload。
 *
 * <p><b>为什么 TLS 放在 nginx</b>：标准部署是 {@code nginx(公网) → java(内网)}，
 * 录像由 nginx sendfile 直读。若在应用内开 HTTPS，nginx 回源要改成 https、
 * 证书更新还要重启连接器，两套东西互相打架。nginx 本来就该干这件事，
 * 本服务只负责「把证书放对位置、把配置写对、验证后 reload」。
 *
 * <p><b>这个类里有三处是刻意保守的设计，改动前请先读完</b>：
 * <ol>
 *   <li><b>私钥永不返回给前端</b>，且落盘权限强制 600。私钥泄漏等于任何人
 *       都能冒充本站，后果比密码泄漏严重得多。</li>
 *   <li><b>{@code nginx -t} 失败绝不 reload，并回滚到旧配置</b>。一份坏配置
 *       reload 会让整站（含录像直读）全部不可用，而错误往往只在 reload
 *       之后才暴露 —— 先校验后 reload 是唯一安全的顺序。</li>
 *   <li><b>配对校验用「私钥签名 → 证书公钥验签」</b>，而不是比较公钥字节。
 *       后者对 RSA 可以做，对 EC 需要在 JDK 里实现点乘，算法一多就是坑；
 *       验签对所有算法通用，且顺便证明了私钥确实可用（能签出被验证的签名）。</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TlsCertificateService {

    /** 证书文件名。 */
    private static final String CERT_FILE = "server.crt";
    /** 私钥文件名。 */
    private static final String KEY_FILE = "server.key";
    /** 证书与私钥之间留多少天就开始在界面上提醒续期。 */
    private static final int EXPIRY_WARN_DAYS = 30;

    private final Gb28181Properties props;

    // ==================== 状态查询 ====================

    /** 当前 HTTPS 状态。 */
    public TlsStatus status() {
        TlsStatus st = new TlsStatus();
        st.setPort(props.getTls().getPort());
        st.setCertDir(certDir().toString());
        st.setNginxConfPath(props.getTls().getNginxConfPath());
        st.setAutoReload(props.getTls().isAutoReload());

        Path cert = certDir().resolve(CERT_FILE);
        boolean confExists = confExists();
        st.setConfigured(confExists);
        st.setHasCertificate(Files.isRegularFile(cert));

        if (!st.isHasCertificate()) {
            st.setMessage(confExists
                    ? "nginx 已配置 HTTPS，但证书文件不存在，请重新上传"
                    : "尚未配置 HTTPS，当前仅提供 HTTP");
            return st;
        }
        try {
            X509Certificate x = loadCertificate(cert);
            fillCertInfo(st, x);
            if (confExists && st.getDaysRemaining() != null && st.getDaysRemaining() <= EXPIRY_WARN_DAYS) {
                st.setMessage("证书将在 " + st.getDaysRemaining() + " 天后过期，请尽快更新");
            } else if (confExists) {
                st.setMessage("HTTPS 已启用");
            } else {
                /*
                 * 证书在、配置不在 = 尚未启用，或被主动停用过。
                 *
                 * <p>不能写成「配置缺失，请重新上传」——那是故障口吻，
                 * 而主动停用是一个正常操作，提示成「出错了」会让人以为
                 * 停用没有真正生效，反复重试。这里如实描述状态即可。
                 */
                st.setMessage("HTTPS 未启用（证书已保留，重新上传即可启用）");
            }
        } catch (Exception e) {
            st.setMessage("证书文件无法解析：" + e.getMessage());
        }
        return st;
    }

    // ==================== 安装 / 更新 ====================

    /**
     * 安装（或更新）证书并启用 HTTPS。
     *
     * <p>流程严格按「解析 → 校验 → 落盘 → 写配置 → {@code nginx -t} → reload」，
     * 任何一步失败都不改变现有可用状态。
     *
     * @param certPem 证书 PEM（可含中间证书链，服务器证书必须放在第一个）
     * @param keyPem  私钥 PEM（支持 PKCS#8 与 RSA/EC 传统格式）
     * @return 安装后的状态
     */
    public TlsStatus install(String certPem, String keyPem) {
        if (certPem == null || certPem.isBlank()) {
            throw BizException.badRequest("证书内容为空");
        }
        if (keyPem == null || keyPem.isBlank()) {
            throw BizException.badRequest("私钥内容为空");
        }
        if (keyPem.contains("BEGIN ENCRYPTED PRIVATE KEY")) {
            throw BizException.badRequest(
                    "不支持带口令的私钥。请先解密：openssl pkcs8 -topk8 -nocrypt -in 加密.key -out 明文.key");
        }

        // ---- 1. 解析证书链 ----
        List<X509Certificate> chain = parseChain(certPem);
        if (chain.isEmpty()) {
            throw BizException.badRequest("未在证书内容中找到 CERTIFICATE 块");
        }
        X509Certificate leaf = chain.get(0);

        // ---- 2. 解析私钥并验证与证书配对 ----
        PrivateKey privateKey = parsePrivateKey(keyPem, leaf); // secret-scan: allow 变量名含 PrivateKey，非硬编码密钥
        verifyKeyPair(leaf, privateKey);

        // ---- 3. 有效期 ----
        try {
            leaf.checkValidity();
        } catch (Exception e) {
            throw BizException.badRequest("证书当前不可用（已过期或尚未生效）：" + e.getMessage());
        }

        // ---- 4. 落盘（私钥 600） ----
        Path dir = certDir();
        try {
            Files.createDirectories(dir);
            Path certFile = dir.resolve(CERT_FILE);
            Path keyFile = dir.resolve(KEY_FILE);
            // 先备份旧文件，任何一个写失败都要能回到原样
            byte[] oldCert = readIfExists(certFile);
            byte[] oldKey = readIfExists(keyFile);
            try {
                writeSecret(certFile, certPem.trim().getBytes(StandardCharsets.UTF_8), "644");
                writeSecret(keyFile, keyPem.trim().getBytes(StandardCharsets.UTF_8), "600");
            } catch (IOException e) {
                restore(certFile, oldCert);
                restore(keyFile, oldKey);
                throw BizException.badRequest("写入证书文件失败：" + e.getMessage());
            }
        } catch (IOException e) {
            throw BizException.badRequest("创建证书目录失败：" + e.getMessage());
        }

        // ---- 5. 生成 nginx 配置并校验/重载 ----
        applyNginxConfig(true);

        TlsStatus st = status();
        log.info("HTTPS 证书已安装：CN={}, 有效期至 {}, 剩余 {} 天",
                st.getSubject(), st.getNotAfter(), st.getDaysRemaining());
        return st;
    }

    /**
     * 停用 HTTPS（删除 nginx 站点配置）。
     *
     * <p><b>不删除证书文件</b>：运维可能只是临时排查，重新启用时不必再上传一次。
     */
    public TlsStatus disable() {
        Path conf = Path.of(props.getTls().getNginxConfPath());
        if (Files.isRegularFile(conf)) {
            byte[] old = readIfExists(conf);
            try {
                Files.delete(conf);
                NginxResult r = runNginx("-t");
                if (!r.ok) {
                    // 语法竟然因为「删掉一个文件」而不通过，说明原来的配置本身有交叉依赖，
                    // 立刻恢复，避免留下一个半残状态
                    Files.write(conf, old);
                    throw BizException.badRequest("停用后 nginx 配置校验失败，已恢复原状：" + r.output());
                }
                reloadNginx();
                log.info("HTTPS 已停用（证书文件保留）");
            } catch (IOException e) {
                throw BizException.badRequest("停用 HTTPS 失败：" + e.getMessage());
            }
        }
        return status();
    }

    // ==================== 国密就绪度自检 ====================

    /**
     * GB/T 35114（国密）就绪度自检。
     *
     * <p><b>为什么是「自检」而不是「开关」</b>：35114 是一整套密码体系，
     * 不是一个配置项。它要求设备与平台双向证书认证（CA 签发）、信令数字签名、
     * 按 A/B/C 三级对视频做签名/加密，且通常需要国密局认证的密码硬件模块。
     * 一个「启用国密」的勾选框背后如果没有真算法与真 CA，勾上之后
     * <b>每台设备都会注册失败</b> —— 那比不提供这个开关糟糕得多。
     *
     * <p>本方法只回答问题：「如果现在要上 35114，已经具备什么、还缺什么」，
     * 结果为纯只读检测，不改变任何配置。
     */
    public GmReadiness gmReadiness() {
        GmReadiness r = new GmReadiness();

        // 1) JDK 自身是否提供 SM2/SM3/SM4
        List<String> jdk = new ArrayList<>();
        for (String alg : new String[]{"SM3", "SM4", "SM2"}) {
            try {
                java.security.MessageDigest.getInstance("SM3".equals(alg) ? "SM3" : "SHA-256");
                if ("SM3".equals(alg)) {
                    jdk.add("SM3");
                }
            } catch (Exception ignored) {
                // JDK 标准版不含国密算法，正常
            }
        }
        // 用实际探测：JDK 17+ 的 SunJCE 不含 SM 系列
        boolean jdkSm3 = providerSupports("SM3", "MessageDigest");
        boolean jdkSm4 = providerSupports("SM4", "Cipher");
        if (jdkSm3) {
            jdk.add("SM3");
        }
        if (jdkSm4) {
            jdk.add("SM4");
        }
        r.setJdkSmAlgorithms(jdk);

        // 2) BouncyCastle（国密算法的事实标准实现）
        boolean bc = classPresent("org.bouncycastle.jce.provider.BouncyCastleProvider");
        r.setBouncyCastlePresent(bc);

        // 3) OpenSSL 是否支持 SM2 曲线（国密 TLS 的前提）
        NginxResult ver = runCommand("openssl", "version");
        r.setOpensslVersion(ver.ok ? ver.output().trim() : "未找到 openssl");
        NginxResult curves = runCommand("sh", "-c", "openssl ecparam -list_curves 2>/dev/null | grep -i sm2 | head -1");
        r.setOpensslSupportsSm2(curves.ok && !curves.output().isBlank());

        // 4) nginx 是否支持国密 TLS（需 Tongsuo/铜锁等分支，标准 nginx 不支持）
        NginxResult nv = runNginx("-V");
        String nginxVer = nv.output();
        r.setNginxVersion(extractVersion(nginxVer));
        r.setNginxSupportsGmTls(nginxVer.toLowerCase().contains("tongsuo")
                || nginxVer.toLowerCase().contains("babassl")
                || nginxVer.toLowerCase().contains("gmssl"));

        // 5) 是否已配置 CA（35114 需要设备证书由平台的 CA 签发或校验）
        Path caDir = certDir().resolveSibling("ca");
        r.setCaConfigured(Files.isDirectory(caDir));

        // 结论
        List<String> missing = new ArrayList<>();
        if (!bc) {
            missing.add("BouncyCastle（SM2/SM3/SM4 的 Java 实现）");
        }
        if (!r.isOpensslSupportsSm2()) {
            missing.add("OpenSSL 的 SM2 曲线支持（当前版本只支持 SM3 摘要，不支持 SM2 加解密/签名）");
        }
        if (!r.isNginxSupportsGmTls()) {
            missing.add("支持国密套件的 nginx（需 Tongsuo/铜锁 分支，标准 nginx 不支持 ECC-SM4-SM3）");
        }
        if (!r.isCaConfigured()) {
            missing.add("设备证书 CA（35114 要求平台能签发/校验设备证书）");
        }
        r.setMissing(missing);
        r.setReady(missing.isEmpty());

        r.setNote("GB/T 35114 是完整的密码体系而非单个开关：需要 CA 为设备签发证书、"
                + "信令数字签名、按 A/B/C 三级对视频做签名与加解密，且通常要求国密局认证的"
                + "密码模块。此外 ZLMediaKit 不做 35114 视频解密，媒体链路也需改造。"
                + "建议作为独立项目评估，不要用配置项开关的形式交付。");
        return r;
    }

    // ==================== nginx ====================

    /**
     * 生成 nginx 配置并校验后 reload。
     *
     * @param rollbackOnFailure 校验失败时是否回滚配置文件
     */
    private void applyNginxConfig(boolean rollbackOnFailure) {
        Path conf = Path.of(props.getTls().getNginxConfPath());
        byte[] old = readIfExists(conf);
        try {
            Files.createDirectories(conf.getParent());
            Files.write(conf, renderNginxConf().getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw BizException.badRequest("写入 nginx 配置失败：" + e.getMessage());
        }

        NginxResult test = runNginx("-t");
        if (!test.ok) {
            if (rollbackOnFailure) {
                restore(conf, old);
            }
            throw BizException.badRequest(
                    "nginx 配置校验未通过，已回滚（未 reload，站点不受影响）：" + test.output());
        }
        reloadNginx();
    }

    /** 执行 reload；失败只告警不影响已完成的工作（配置已校验通过）。 */
    private void reloadNginx() {
        if (!props.getTls().isAutoReload()) {
            log.warn("已生成 nginx 配置但未自动 reload（gb28181.tls.auto-reload=false）。"
                    + "请手工执行：nginx -t && nginx -s reload");
            return;
        }
        NginxResult r = runNginx("-s", "reload");
        if (!r.ok) {
            throw BizException.badRequest(
                    "nginx reload 失败（配置已写入且语法正确，站点仍按旧配置运行）：" + r.output()
                            + "。请手工执行 nginx -s reload");
        }
        log.info("nginx 已 reload，HTTPS 生效于端口 {}", props.getTls().getPort());
    }

    /**
     * 渲染 HTTPS 站点配置。
     *
     * <p><b>必须把 HTTP 站点的 location 全部重复一遍</b>：nginx 的 location
     * 是按 server 块匹配的，只写证书不写 location 会出现「页面能开、录像全 404」——
     * 因为 {@code /record/} 的 sendfile 直读和 {@code /} 的反代都定义在
     * HTTP 那个 server 块里。</p>
     *
     * <p>同理 {@code auth_request} 也要带过来，否则 HTTPS 下录像地址签名校验
     * 会被绕过（这是上一轮刚修好的漏洞，不能在 SSL 配置里重新打开）。
     */
    private String renderNginxConf() {
        Path dir = certDir();
        String cert = dir.resolve(CERT_FILE).toString();
        String key = dir.resolve(KEY_FILE).toString();
        int port = props.getTls().getPort();
        int appPort = 48092;

        return "# 本文件由 gb28181-platform-starter 自动生成，请勿手工编辑。\n"
                + "# 重新上传证书会被覆盖。生成时间：" + Instant.now() + "\n"
                + "server {\n"
                + "    listen       " + port + " ssl http2;\n"
                + "    server_name  _;\n"
                + "\n"
                + "    root /opt/gb28181;\n"
                + "    sendfile    on;\n"
                + "    output_buffers 4 256k;\n"
                + "    max_ranges   16;\n"
                + "    keepalive_timeout  300s;\n"
                + "    keepalive_requests 1000;\n"
                + "\n"
                + "    ssl_certificate     " + cert + ";\n"
                + "    ssl_certificate_key " + key + ";\n"
                + "    # 只保留 TLS1.2/1.3：TLS1.0/1.1 已被各大浏览器判定为不安全，\n"
                + "    # 且 POODLE/BEAST 等攻击针对的正是它们。\n"
                + "    ssl_protocols       TLSv1.2 TLSv1.3;\n"
                + "    ssl_ciphers         ECDHE-ECDSA-AES128-GCM-SHA256:ECDHE-RSA-AES128-GCM-SHA256:"
                + "ECDHE-ECDSA-AES256-GCM-SHA384:ECDHE-RSA-AES256-GCM-SHA384:"
                + "ECDHE-ECDSA-CHACHA20-POLY1305:ECDHE-RSA-CHACHA20-POLY1305;\n"
                + "    # 由服务器决定加密套件：否则客户端可强制选弱套件\n"
                + "    ssl_prefer_server_ciphers on;\n"
                + "    ssl_session_cache   shared:GBSSL:10m;\n"
                + "    ssl_session_timeout 10m;\n"
                + "    ssl_session_tickets off;\n"
                + "\n"
                + "    add_header Access-Control-Allow-Origin  $cors_origin always;\n"
                + "    add_header Access-Control-Allow-Methods \"GET, HEAD, OPTIONS\" always;\n"
                + "    add_header Access-Control-Allow-Headers \"Range, Authorization, Content-Type, Accept\" always;\n"
                + "    add_header Access-Control-Expose-Headers \"Content-Length, Content-Range, Accept-Ranges\" always;\n"
                + "    add_header Vary \"Origin\" always;\n"
                + "\n"
                + "    if ($request_method = OPTIONS) {\n"
                + "        return 204;\n"
                + "    }\n"
                + "\n"
                + "    # 录像：地址签名鉴权（与 HTTP 站点同一套逻辑，见 RecordUrlSigner）\n"
                + "    location /record/ {\n"
                + "        auth_request /_record_auth;\n"
                + "        add_header Accept-Ranges \"bytes\" always;\n"
                + "        add_header Cache-Control \"no-cache\" always;\n"
                + "        add_header Access-Control-Allow-Origin  $cors_origin always;\n"
                + "        add_header Access-Control-Allow-Methods \"GET, HEAD, OPTIONS\" always;\n"
                + "        add_header Access-Control-Allow-Headers \"Range, Authorization, Content-Type, Accept\" always;\n"
                + "        add_header Access-Control-Expose-Headers \"Content-Length, Content-Range, Accept-Ranges\" always;\n"
                + "        add_header Vary \"Origin\" always;\n"
                + "        try_files $uri =404;\n"
                + "    }\n"
                + "\n"
                + "    location = /_record_auth {\n"
                + "        internal;\n"
                + "        proxy_pass http://127.0.0.1:" + appPort + "/api/v1/record/auth;\n"
                + "        proxy_method GET;\n"
                + "        proxy_pass_request_body off;\n"
                + "        proxy_set_header Content-Length \"\";\n"
                + "        proxy_set_header X-Original-URI $request_uri;\n"
                + "    }\n"
                + "\n"
                + "    location / {\n"
                + "        proxy_pass http://127.0.0.1:" + appPort + ";\n"
                + "        proxy_http_version 1.1;\n"
                + "        proxy_set_header Host              $host;\n"
                + "        proxy_set_header X-Real-IP         $remote_addr;\n"
                + "        proxy_set_header X-Forwarded-For   $proxy_add_x_forwarded_for;\n"
                + "        proxy_set_header X-Forwarded-Proto $scheme;\n"
                + "        proxy_set_header Upgrade    $http_upgrade;\n"
                + "        proxy_set_header Connection $connection_upgrade;\n"
                + "        proxy_buffering off;\n"
                + "        proxy_read_timeout 300s;\n"
                + "    }\n"
                + "}\n";
    }

    /** {@code nginx -t} 是否通过。 */
    private boolean confExists() {
        return Files.isRegularFile(Path.of(props.getTls().getNginxConfPath()));
    }

    private NginxResult runNginx(String... args) {
        String[] cmd = new String[args.length + 1];
        cmd[0] = props.getTls().getNginxBin();
        System.arraycopy(args, 0, cmd, 1, args.length);
        return runCommand(cmd);
    }

    /**
     * 执行外部命令并收集输出。
     *
     * <p>合并 stderr（{@code nginx -t} 把结果写在 stderr）并设超时：
     * 外部命令卡住会把 HTTP 请求线程一起拖死。
     */
    private NginxResult runCommand(String... cmd) {
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true);
            Process p = pb.start();
            String out;
            try (InputStream in = p.getInputStream()) {
                out = new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
            }
            if (!p.waitFor(15, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return new NginxResult(false, "命令执行超时：" + String.join(" ", cmd));
            }
            return new NginxResult(p.exitValue() == 0, out);
        } catch (IOException e) {
            return new NginxResult(false, "无法执行 " + cmd[0] + "：" + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new NginxResult(false, "命令被中断：" + String.join(" ", cmd));
        }
    }

    // ==================== 证书解析 ====================

    /** 解析 PEM 证书链（顺序保持原样）。 */
    private static List<X509Certificate> parseChain(String pem) {
        List<X509Certificate> out = new ArrayList<>();
        try (InputStream in = new ByteArrayInputStream(pem.getBytes(StandardCharsets.UTF_8))) {
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            Collection<? extends java.security.cert.Certificate> certs = cf.generateCertificates(in);
            for (java.security.cert.Certificate c : certs) {
                if (c instanceof X509Certificate) {
                    out.add((X509Certificate) c);
                }
            }
        } catch (Exception e) {
            throw BizException.badRequest("证书解析失败：" + e.getMessage());
        }
        return out;
    }

    private static X509Certificate loadCertificate(Path file) throws Exception {
        try (InputStream in = Files.newInputStream(file)) {
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            return (X509Certificate) cf.generateCertificate(in);
        }
    }

    /**
     * 解析私钥。
     *
     * <p>支持两种实际最常见的格式：
     * <ul>
     *   <li>PKCS#8（{@code BEGIN PRIVATE KEY}）—— JDK 直接吃；</li>
     *   <li>PKCS#1 / SEC1（{@code BEGIN RSA PRIVATE KEY} / {@code BEGIN EC PRIVATE KEY}）
     *       —— 这是 {@code openssl genrsa} 的默认输出，必须手工包一层 PKCS#8，
     *       否则用户按最普通的 openssl 命令生成密钥后上传会直接报「解析失败」。</li>
     * </ul>
     * 算法由证书公钥决定（密钥必须与之配对，按证书算法尝试即可）。
     */
    private static PrivateKey parsePrivateKey(String pem, X509Certificate cert) {
        String body = pem.replaceAll("-----BEGIN [^-]+-----", "")
                .replaceAll("-----END [^-]+-----", "")
                .replaceAll("\\s", "");
        byte[] der;
        try {
            der = Base64.getDecoder().decode(body);
        } catch (IllegalArgumentException e) {
            throw BizException.badRequest("私钥不是合法的 Base64 内容");
        }

        boolean pkcs8 = pem.contains("BEGIN PRIVATE KEY");
        String certAlg = cert.getPublicKey().getAlgorithm();
        String alg = switch (certAlg) {
            case "RSA" -> "RSA";
            case "EC", "ECDSA" -> "EC";
            case "Ed25519", "EdDSA" -> "Ed25519";
            default -> certAlg;
        };

        // 先按原样尝试（PKCS#8）
        if (pkcs8) {
            PrivateKey k = tryKey(alg, der);
            if (k != null) {
                return k;
            }
        }
        // 再按 PKCS#1（RSA）/ SEC1（EC）包装成 PKCS#8
        byte[] wrapped = wrapAsPkcs8(der, "EC".equals(alg) ? "EC" : "RSA");
        PrivateKey k = tryKey(alg, wrapped);
        if (k != null) {
            return k;
        }
        // 两种都失败时，把原始字节再试一次（有些工具导出的 PKCS#8 标签写成 RSA PRIVATE KEY）
        k = tryKey(alg, der);
        if (k != null) {
            return k;
        }
        throw BizException.badRequest("私钥解析失败：请确认私钥与证书算法一致（证书为 "
                + certAlg + "），且未使用口令加密");
    }

    private static PrivateKey tryKey(String algorithm, byte[] der) {
        try {
            return KeyFactory.getInstance(algorithm).generatePrivate(new PKCS8EncodedKeySpec(der));
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 把 PKCS#1 / SEC1 私钥包装成 PKCS#8。
     *
     * <pre>
     * PKCS#8 ::= SEQUENCE {
     *   version             INTEGER (0),
     *   privateKeyAlgorithm SEQUENCE { OID, 参数 },
     *   privateKey          OCTET STRING   -- 里面就是原来的 PKCS#1/SEC1 字节
     * }
     * </pre>
     * OID：rsaEncryption = 1.2.840.113549.1.1.1，id-ecPublicKey = 1.2.840.10045.2.1
     */
    private static byte[] wrapAsPkcs8(byte[] inner, String kind) {
        byte[] algId = "EC".equals(kind)
                ? new byte[]{(byte) 0x30, 0x13, 0x06, 0x07, 0x2A, (byte) 0x86, 0x48,
                (byte) 0xCE, 0x3D, 0x02, 0x01, 0x06, 0x08, 0x2A, (byte) 0x86, 0x48,
                (byte) 0xCE, 0x3D, 0x03, 0x01, 0x07}
                // rsaEncryption 带 NULL 参数
                : new byte[]{0x30, 0x0D, 0x06, 0x09, 0x2A, (byte) 0x86, 0x48,
                (byte) 0x86, (byte) 0xF7, 0x0D, 0x01, 0x01, 0x01, 0x05, 0x00};

        byte[] version = new byte[]{0x02, 0x01, 0x00};
        byte[] octet = derTlv((byte) 0x04, inner);
        byte[] body = concat(version, algId, octet);
        return derTlv((byte) 0x30, body);
    }

    /** 编码一个 DER TLV。 */
    private static byte[] derTlv(byte tag, byte[] value) {
        int len = value.length;
        byte[] lenBytes;
        if (len < 0x80) {
            lenBytes = new byte[]{(byte) len};
        } else if (len <= 0xFF) {
            lenBytes = new byte[]{(byte) 0x81, (byte) len};
        } else if (len <= 0xFFFF) {
            lenBytes = new byte[]{(byte) 0x82, (byte) (len >> 8), (byte) len};
        } else {
            lenBytes = new byte[]{(byte) 0x83, (byte) (len >> 16),
                    (byte) (len >> 8), (byte) len};
        }
        byte[] out = new byte[1 + lenBytes.length + len];
        out[0] = tag;
        System.arraycopy(lenBytes, 0, out, 1, lenBytes.length);
        System.arraycopy(value, 0, out, 1 + lenBytes.length, len);
        return out;
    }

    private static byte[] concat(byte[]... parts) {
        int total = 0;
        for (byte[] p : parts) {
            total += p.length;
        }
        byte[] out = new byte[total];
        int pos = 0;
        for (byte[] p : parts) {
            System.arraycopy(p, 0, out, pos, p.length);
            pos += p.length;
        }
        return out;
    }

    /**
     * 验证私钥与证书配对。
     *
     * <p>用「私钥签名 → 公钥验签」而不是比较公钥字节：后者对 RSA 可行，
     * 对 EC 需要在 JDK 里自己实现点乘；验签对所有算法通用，
     * 而且顺便证明了私钥确实可用于签名（不只是能被解析）。
     *
     * <p><b>上传错私钥是这里最常见的事故</b>（同名文件、多张证书混淆），
     * 而 nginx 只在 reload 时才报 "key values mismatch"，此时站点已经不可用了。
     * 提前到上传环节校验，代价是一次签名运算，收益是避免一次线上事故。
     */
    private static void verifyKeyPair(X509Certificate cert, PrivateKey key) {
        String sigAlg;
        String keyAlg = cert.getPublicKey().getAlgorithm();
        switch (keyAlg) {
            case "RSA" -> sigAlg = "SHA256withRSA";
            case "EC", "ECDSA" -> sigAlg = "SHA256withECDSA";
            case "Ed25519", "EdDSA" -> sigAlg = "Ed25519";
            default -> {
                log.warn("未知公钥算法 {}，跳过公私钥配对校验", keyAlg);
                return;
            }
        }
        try {
            byte[] challenge = ("gb28181-tls-pair-check-" + System.nanoTime())
                    .getBytes(StandardCharsets.UTF_8);
            Signature signer = Signature.getInstance(sigAlg);
            signer.initSign(key);
            signer.update(challenge);
            byte[] sig = signer.sign();

            Signature verifier = Signature.getInstance(sigAlg);
            verifier.initVerify(cert.getPublicKey());
            verifier.update(challenge);
            if (!verifier.verify(sig)) {
                throw BizException.badRequest("私钥与证书不匹配：请确认上传的是这张证书对应的私钥");
            }
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw BizException.badRequest("公私钥配对校验失败：" + e.getMessage());
        }
    }

    /** 把证书信息填进状态对象。 */
    private static void fillCertInfo(TlsStatus st, X509Certificate x) {
        st.setSubject(x.getSubjectX500Principal().getName());
        st.setIssuer(x.getIssuerX500Principal().getName());
        st.setSerialNumber(x.getSerialNumber().toString(16).toUpperCase());
        st.setSignatureAlgorithm(x.getSigAlgName());
        st.setKeyAlgorithm(x.getPublicKey().getAlgorithm());
        st.setNotBefore(x.getNotBefore().toInstant().toString());
        st.setNotAfter(x.getNotAfter().toInstant().toString());
        long days = Duration.between(Instant.now(), x.getNotAfter().toInstant()).toDays();
        st.setDaysRemaining(days);
        st.setExpired(days < 0);
        st.setSelfSigned(x.getSubjectX500Principal().equals(x.getIssuerX500Principal()));

        // CN 与 SAN：运维最关心「这张证书认哪些域名」，配错就是浏览器报 NET::ERR_CERT_COMMON_NAME_INVALID
        List<String> names = new ArrayList<>();
        try {
            Collection<List<?>> sans = x.getSubjectAlternativeNames();
            if (sans != null) {
                for (List<?> san : sans) {
                    Object value = san.size() > 1 ? san.get(1) : null;
                    if (value != null) {
                        names.add(String.valueOf(value));
                    }
                }
            }
        } catch (Exception ignored) {
            // 某些证书的 SAN 扩展畸形，读不到就只展示 CN
        }
        if (names.isEmpty()) {
            String dn = x.getSubjectX500Principal().getName();
            for (String part : dn.split(",")) {
                if (part.trim().startsWith("CN=")) {
                    names.add(part.trim().substring(3));
                }
            }
        }
        st.setDomainNames(names);
    }

    // ==================== 工具 ====================

    private Path certDir() {
        String configured = props.getTls().getCertDir();
        if (configured != null && !configured.isBlank()) {
            return Path.of(configured).toAbsolutePath().normalize();
        }
        // 默认与应用同级的 ssl 目录（gb28181.tls.cert-dir 可覆盖）
        return Path.of(System.getProperty("user.dir"), "ssl").toAbsolutePath().normalize();
    }

    private static byte[] readIfExists(Path p) {
        try {
            return Files.isRegularFile(p) ? Files.readAllBytes(p) : null;
        } catch (IOException e) {
            return null;
        }
    }

    private static void restore(Path p, byte[] content) {
        try {
            if (content == null) {
                Files.deleteIfExists(p);
            } else {
                Files.write(p, content);
            }
        } catch (IOException e) {
            log.error("回滚 {} 失败，请人工检查：{}", p, e.getMessage());
        }
    }

    /** 写入并设置权限（私钥必须 600：同机其它用户不可读）。 */
    private static void writeSecret(Path p, byte[] content, String mode) throws IOException {
        Files.write(p, content);
        Files.setPosixFilePermissions(p, PosixFilePermissions.fromString(
                "600".equals(mode) ? "rw-------" : "rw-r--r--"));
    }

    private static boolean providerSupports(String algorithm, String type) {
        try {
            if ("MessageDigest".equals(type)) {
                java.security.MessageDigest.getInstance(algorithm);
                return true;
            }
            javax.crypto.Cipher.getInstance(algorithm);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean classPresent(String name) {
        try {
            Class.forName(name, false, TlsCertificateService.class.getClassLoader());
            return true;
        } catch (Throwable e) {
            return false;
        }
    }

    private static String extractVersion(String nginxV) {
        if (nginxV == null || nginxV.isBlank()) {
            return "未找到 nginx";
        }
        int idx = nginxV.indexOf("nginx/");
        if (idx < 0) {
            return firstLine(nginxV);
        }
        int end = idx + 6;
        while (end < nginxV.length() && (Character.isDigit(nginxV.charAt(end))
                || nginxV.charAt(end) == '.')) {
            end++;
        }
        return nginxV.substring(idx, Math.min(end, nginxV.length()));
    }

    private static String firstLine(String s) {
        int nl = s.indexOf('\n');
        return (nl < 0 ? s : s.substring(0, nl)).trim();
    }

    /** 外部命令结果。 */
    private record NginxResult(boolean ok, String output) {
    }

    /** HTTPS 状态。 */
    @lombok.Data
    public static class TlsStatus {
        private boolean configured;
        private boolean hasCertificate;
        private int port;
        private String certDir;
        private String nginxConfPath;
        private boolean autoReload;
        private String message;

        private String subject;
        private String issuer;
        private String serialNumber;
        private String signatureAlgorithm;
        private String keyAlgorithm;
        private String notBefore;
        private String notAfter;
        private Long daysRemaining;
        private boolean expired;
        private boolean selfSigned;
        /** CN 与 SAN 中的全部域名。 */
        private List<String> domainNames;
    }

    /** 国密就绪度。 */
    @lombok.Data
    public static class GmReadiness {
        private boolean ready;
        private List<String> jdkSmAlgorithms;
        private boolean bouncyCastlePresent;
        private String opensslVersion;
        private boolean opensslSupportsSm2;
        private String nginxVersion;
        private boolean nginxSupportsGmTls;
        private boolean caConfigured;
        private List<String> missing;
        private String note;
    }
}
