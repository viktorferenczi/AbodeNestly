// Local stand-in for Vercel's router: maps a URL path to a handler in api/ and bridges
// node:http to the Web Request/Response handlers. Used by `pnpm dev` and integration tests.
import { createServer, type IncomingMessage, type Server, type ServerResponse } from 'node:http';
import { fileURLToPath } from 'node:url';
import health from '../health.ts';
import households from '../households/index.ts';
import householdInvites from '../households/[id]/invites.ts';
import acceptInvite from '../invites/[code]/accept.ts';

type Handler = (req: Request) => Promise<Response>;

const routes: Record<string, Handler> = {
  '/health': health,
  '/households': households,
};

// Vercel's [param] routes, as path patterns.
const patterns: Array<[RegExp, Handler]> = [
  [/^\/households\/[^/]+\/invites$/, householdInvites],
  [/^\/invites\/[^/]+\/accept$/, acceptInvite],
];

async function toRequest(req: IncomingMessage): Promise<Request> {
  const chunks: Buffer[] = [];
  for await (const chunk of req) chunks.push(chunk as Buffer);
  const body = chunks.length ? Buffer.concat(chunks) : undefined;
  const headers = new Headers();
  for (const [key, value] of Object.entries(req.headers)) {
    if (value !== undefined) headers.set(key, Array.isArray(value) ? value.join(', ') : value);
  }
  return new Request(`http://${req.headers.host ?? 'localhost'}${req.url ?? '/'}`, {
    method: req.method,
    headers,
    body: req.method === 'GET' || req.method === 'HEAD' ? undefined : body,
  });
}

async function send(res: ServerResponse, response: Response): Promise<void> {
  res.statusCode = response.status;
  response.headers.forEach((value, key) => res.setHeader(key, value));
  res.end(Buffer.from(await response.arrayBuffer()));
}

export function createDevServer(): Server {
  return createServer(async (req, res) => {
    const path = new URL(req.url ?? '/', 'http://localhost').pathname;
    const handler = routes[path] ?? patterns.find(([re]) => re.test(path))?.[1];
    if (!handler) {
      res.statusCode = 404;
      res.setHeader('content-type', 'application/json; charset=utf-8');
      res.end(JSON.stringify({ error: { code: 'not_found', message: `No route ${path}` } }));
      return;
    }
    await send(res, await handler(await toRequest(req)));
  });
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  const port = Number(process.env.PORT ?? 3000);
  createDevServer().listen(port, () => console.log(`api listening on http://localhost:${port}`));
}
