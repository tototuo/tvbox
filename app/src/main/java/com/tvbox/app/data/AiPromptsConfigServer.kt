package com.tvbox.app.data

import com.tvbox.app.domain.MAX_AI_QUICK_PROMPTS
import com.tvbox.app.domain.MAX_AI_QUICK_PROMPT_LENGTH
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.UUID

/**
 * 手机扫码编辑「AI 快捷推荐词」：电视上起一个一次性的局域网网页，手机里一行一个编辑后提交。
 * 写法与 [AiConfigServer] 一致（token 校验、只接受 /save 的 POST）。
 */
class AiPromptsConfigServer(
    private val scope: CoroutineScope,
    private val onSubmit: (promptsText: String) -> Unit,
) {
    private var serverSocket: ServerSocket? = null
    private var serverJob: Job? = null

    suspend fun start(currentPrompts: List<String>): AiConfigServerSession = withContext(Dispatchers.IO) {
        stop()
        val token = UUID.randomUUID().toString().replace("-", "")
        val socket = runCatching { ServerSocket(PREFERRED_PORT) }.getOrElse { ServerSocket(0) }
        serverSocket = socket
        val host = localIpv4Address()
        serverJob = scope.launch(Dispatchers.IO) {
            while (isActive && !socket.isClosed) {
                val client = runCatching { socket.accept() }.getOrNull() ?: break
                launch { runCatching { handleClient(client, token, currentPrompts) } }
            }
        }
        AiConfigServerSession(url = "http://$host:${socket.localPort}/?token=$token", host = host, port = socket.localPort)
    }

    fun stop() {
        serverJob?.cancel()
        serverJob = null
        runCatching { serverSocket?.close() }
        serverSocket = null
    }

    private fun handleClient(client: Socket, token: String, currentPrompts: List<String>) {
        client.use { socket ->
            val reader = BufferedReader(InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))
            val requestLine = reader.readLine().orEmpty()
            if (requestLine.isBlank()) return
            val parts = requestLine.split(" ")
            val method = parts.getOrNull(0).orEmpty()
            val target = parts.getOrNull(1).orEmpty()
            var contentLength = 0
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
                val separator = line.indexOf(':')
                if (separator > 0 && line.substring(0, separator).trim().equals("content-length", ignoreCase = true)) {
                    contentLength = line.substring(separator + 1).trim().toIntOrNull() ?: 0
                }
            }
            when (method) {
                "GET" -> writeHtml(socket, editPage(token, currentPrompts.joinToString("\n")))
                "POST" -> {
                    // Content-Length 是字节数，表单是 URL 编码的纯 ASCII，按字符读即可
                    val body = CharArray(contentLength.coerceIn(0, MAX_BODY_CHARS))
                    var total = 0
                    while (total < body.size) {
                        val read = reader.read(body, total, body.size - total)
                        if (read <= 0) break
                        total += read
                    }
                    val form = parseForm(String(body, 0, total))
                    if (form["token"] != token || !target.startsWith("/save")) {
                        writeHtml(socket, "请求已失效，请回到电视重新打开二维码。", status = "403 Forbidden")
                        return
                    }
                    onSubmit(form["prompts"].orEmpty())
                    writeHtml(socket, savedPage())
                }
                else -> writeHtml(socket, "不支持的请求。", status = "405 Method Not Allowed")
            }
        }
    }

    private fun writeHtml(socket: Socket, body: String, status: String = "200 OK") {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        val header = "HTTP/1.1 $status\r\nContent-Type: text/html; charset=utf-8\r\n" +
            "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
        socket.getOutputStream().apply {
            write(header.toByteArray(StandardCharsets.US_ASCII))
            write(bytes)
            flush()
        }
    }

    private fun editPage(token: String, promptsText: String): String = page(
        title = "TVBox 快捷推荐词",
        body = """
            <h1>AI 快捷推荐词</h1>
            <div class="panel">
              <p>一行一个，最多 $MAX_AI_QUICK_PROMPTS 个，每个不超过 $MAX_AI_QUICK_PROMPT_LENGTH 个字。按这里的顺序显示在电视「推荐」页。</p>
              <p>全部清空后提交会恢复默认推荐词。</p>
              <form method="post" action="/save">
                <input type="hidden" name="token" value="$token" />
                <textarea name="prompts" rows="10" autocomplete="off">${promptsText.htmlEscape()}</textarea>
                <button type="submit">确认同步到电视</button>
              </form>
            </div>
        """,
    )

    private fun savedPage(): String = page(
        title = "已保存",
        body = """
            <h1 class="ok">已同步到电视</h1>
            <p>回到电视的「推荐」页就能看到新的快捷推荐词。</p>
        """,
    )

    private fun page(title: String, body: String): String = """
        <!doctype html>
        <html lang="zh-CN">
        <head>
          <meta charset="utf-8" />
          <meta name="viewport" content="width=device-width, initial-scale=1" />
          <title>$title</title>
          <style>
            body { margin: 0; font-family: sans-serif; background: #10141f; color: #f5f7fb; }
            main { max-width: 520px; margin: 0 auto; padding: 28px 18px; }
            h1 { font-size: 26px; margin: 0 0 10px; }
            .ok { color: #28d0b0; }
            p { color: #b8c0cf; line-height: 1.6; }
            textarea { width: 100%; box-sizing: border-box; border: 1px solid #657084; border-radius: 10px; padding: 14px; font-size: 17px; line-height: 1.6; background: #171d2a; color: #fff; }
            button { width: 100%; margin-top: 18px; border: 0; border-radius: 10px; padding: 15px; font-size: 18px; font-weight: 700; color: #051b17; background: #28d0b0; }
            .panel { background: #1a2130; border: 1px solid #2f3a4f; border-radius: 14px; padding: 18px; }
          </style>
        </head>
        <body><main>$body</main></body>
        </html>
    """.trimIndent()

    private fun parseForm(body: String): Map<String, String> =
        body.split('&').mapNotNull { pair ->
            val separator = pair.indexOf('=')
            if (separator < 0) null
            else URLDecoder.decode(pair.substring(0, separator), "UTF-8") to
                URLDecoder.decode(pair.substring(separator + 1), "UTF-8")
        }.toMap()

    private fun localIpv4Address(): String =
        NetworkInterface.getNetworkInterfaces().toList().asSequence()
            .flatMap { it.inetAddresses.toList().asSequence() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { !it.isLoopbackAddress && it.isSiteLocalAddress }
            ?.hostAddress ?: "127.0.0.1"

    private fun String.htmlEscape(): String =
        replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;")

    private companion object {
        const val PREFERRED_PORT = 9979
        const val MAX_BODY_CHARS = 64 * 1024
    }
}
