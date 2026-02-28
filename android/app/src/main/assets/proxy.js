const http = require("http");
const net = require("net");
const fs = require("fs");
const path = require("path");

const PORT = 18924;

const pidFile = path.join(process.env.HOME || "/tmp", ".proxy.pid");
fs.writeFileSync(pidFile, String(process.pid));
process.on("exit", () => { try { fs.unlinkSync(pidFile); } catch {} });

const server = http.createServer((req, res) => {
  try {
    const url = new URL(req.url);
    const options = {
      hostname: url.hostname,
      port: url.port || 80,
      path: url.pathname + url.search,
      method: req.method,
      headers: req.headers,
    };
    delete options.headers.host;
    const proxy = http.request(options, (pRes) => {
      res.writeHead(pRes.statusCode, pRes.headers);
      pRes.pipe(res);
    });
    req.pipe(proxy);
    proxy.on("error", (e) => {
      res.writeHead(502);
      res.end(e.message);
    });
  } catch (e) {
    res.writeHead(500);
    res.end(e.message);
  }
});

server.on("connect", (req, clientSocket) => {
  const [host, port] = req.url.split(":");
  const conn = net.connect(parseInt(port) || 443, host, () => {
    clientSocket.write("HTTP/1.1 200 Connection Established\r\n\r\n");
    conn.pipe(clientSocket);
    clientSocket.pipe(conn);
  });
  conn.on("error", () => clientSocket.end());
  clientSocket.on("error", () => conn.end());
});

let retries = 0;
function tryListen() {
  server.listen(PORT, "127.0.0.1", () => {
    console.log(`CONNECT proxy listening on 127.0.0.1:${PORT}`);
  });
}

server.on("error", (e) => {
  if (e.code === "EADDRINUSE" && retries < 3) {
    retries++;
    console.log(`Port ${PORT} in use (attempt ${retries}/3), retrying…`);
    server.close();
    setTimeout(tryListen, 1000);
  } else {
    console.error("Proxy error:", e.message);
    process.exit(1);
  }
});

tryListen();
