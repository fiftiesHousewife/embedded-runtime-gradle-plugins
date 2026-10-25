import { createServer } from 'node:http';

const port = Number(process.env.APP_PORT ?? 8080);
if (process.argv.includes('--healthcheck')) {
  const ok = await fetch(`http://127.0.0.1:${port}/`).then(r => r.ok, () => false);
  process.exit(ok ? 0 : 1);
}
createServer((_, res) => res.end('ok')).listen(port);
