import { Env } from '../types';
import { errorResponse } from '../utils';
import {
  handleAccountLogin,
  handleAccountLogout,
  handleAccountRefresh,
  handleClaimLegacy,
  handleGetAccount,
} from './auth';
import { handleSyncHead, handleSyncPull, handleSyncPush, handleSyncSnapshot } from './sync';
import {
  handleGetCurrentTransfer,
  handleSourceFrozen,
  handleSourceWiped,
  handleTargetConfirmed,
} from './transfers';

export async function handleAccountRoute(request: Request, env: Env): Promise<Response> {
  const path = new URL(request.url).pathname;
  const method = request.method;

  if (method === 'POST' && path === '/v2/accounts/claim-legacy') return handleClaimLegacy(request, env);
  if (method === 'POST' && path === '/v2/auth/login') return handleAccountLogin(request, env);
  if (method === 'POST' && path === '/v2/auth/refresh') return handleAccountRefresh(request, env);
  if (method === 'POST' && path === '/v2/auth/logout') return handleAccountLogout(request, env);
  if (method === 'GET' && path === '/v2/account') return handleGetAccount(request, env);

  if (method === 'GET' && path === '/v2/sync/head') return handleSyncHead(request, env);
  if (method === 'GET' && path === '/v2/sync/pull') return handleSyncPull(request, env);
  if (method === 'POST' && path === '/v2/sync/push') return handleSyncPush(request, env);
  if (method === 'GET' && path === '/v2/sync/snapshot') return handleSyncSnapshot(request, env);

  if (method === 'GET' && path === '/v2/transfers/current') return handleGetCurrentTransfer(request, env);
  const transferMatch = path.match(/^\/v2\/transfers\/([A-Za-z0-9_-]{16,80})\/(source-frozen|target-confirmed|source-wiped)$/);
  if (method === 'POST' && transferMatch) {
    const [, transferId, action] = transferMatch;
    if (action === 'source-frozen') return handleSourceFrozen(request, env, transferId);
    if (action === 'target-confirmed') return handleTargetConfirmed(request, env, transferId);
    return handleSourceWiped(request, env, transferId);
  }

  return errorResponse('Not found', 404, 'NOT_FOUND');
}
