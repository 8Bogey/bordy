import { Env } from './types';
import { handleActivate } from './handlers/activate';
import { handleRefresh } from './handlers/refresh';
import { handleGetPlans } from './handlers/plans';
import { handleSync } from './handlers/sync';
import { handleGetTos } from './handlers/tos';
import { handleGetUpdate } from './handlers/update';
import { handleCheckCode, handleRedeem } from './handlers/codes';
import { handleCreateOrder, handleSubmitProof, handleGetOrder, handleCancelOrder } from './handlers/orders';
import { handleTransferRequest } from './handlers/transfer';
import { handleTelegramWebhook } from './telegram';
import { handleAdminPanel } from './admin/panel';
import { handleScheduled } from './cron';
import { handleAccountRoute } from './account/router';
import { errorResponse } from './utils';

export default {
  async fetch(request: Request, env: Env, ctx: ExecutionContext): Promise<Response> {
    const url = new URL(request.url);
    const path = url.pathname;
    const method = request.method;

    // Native Android does not need CORS. Keep the legacy public endpoints compatible,
    // but never expose account bearer tokens to cross-origin browser code.
    if (method === 'OPTIONS') {
      if (path.startsWith('/v2/') || path.startsWith('/admin')) {
        return new Response(null, { status: 204, headers: { 'Cache-Control': 'no-store' } });
      }
      return new Response(null, {
        headers: {
          'Access-Control-Allow-Origin': '*',
          'Access-Control-Allow-Methods': 'GET, POST, OPTIONS',
          'Access-Control-Allow-Headers': 'Content-Type',
        },
      });
    }

    try {
      // --- API Routes ---

      if (path.startsWith('/v2/')) {
        return handleAccountRoute(request, env);
      }

      // Activation
      if (method === 'POST' && path === '/v1/activate') {
        return handleActivate(request, env);
      }

      // Token Refresh
      if (method === 'POST' && path === '/v1/refresh') {
        return handleRefresh(request, env);
      }

      // Plans
      if (method === 'GET' && path === '/v1/plans') {
        return handleGetPlans(request, env);
      }

      // Unified sync (license + config + latest order)
      if (method === 'GET' && path === '/v1/sync') {
        return handleSync(request, env);
      }

      // TOS
      if (method === 'GET' && path === '/v1/tos') {
        return handleGetTos(request, env);
      }

      // Update check
      if (method === 'GET' && path === '/v1/update') {
        return handleGetUpdate(request, env);
      }

      // Code check
      if (method === 'POST' && path === '/v1/codes/check') {
        return handleCheckCode(request, env);
      }

      // Code redeem
      if (method === 'POST' && path === '/v1/redeem') {
        return handleRedeem(request, env);
      }

      // Create order
      if (method === 'POST' && path === '/v1/orders') {
        return handleCreateOrder(request, env);
      }

      // Order routes with ID
      const orderMatch = path.match(/^\/v1\/orders\/([A-Z0-9-]+)(\/.*)?$/);
      if (orderMatch) {
        const orderId = orderMatch[1];
        const subPath = orderMatch[2] || '';

        if (method === 'POST' && subPath === '/proof') {
          return handleSubmitProof(request, env, orderId);
        }
        if (method === 'GET' && subPath === '') {
          return handleGetOrder(request, env, orderId);
        }
        if (method === 'POST' && subPath === '/cancel') {
          return handleCancelOrder(request, env, orderId);
        }
      }

      // Transfer
      if (method === 'POST' && path === '/v1/transfer/request') {
        return handleTransferRequest(request, env);
      }

      // Telegram webhook
      const tgPath = `/tg/${env.TG_WEBHOOK_SECRET}`;
      if (method === 'POST' && path === tgPath) {
        return handleTelegramWebhook(request, env);
      }

      // Admin panel
      if (path.startsWith('/admin')) {
        return handleAdminPanel(request, env);
      }

      // 404
      return errorResponse('Not found', 404);

    } catch (e) {
      const message = e instanceof Error ? e.message : 'Internal error';
      console.error('Unhandled error:', e);
      return errorResponse(message, 500);
    }
  },

  async scheduled(event: ScheduledEvent, env: Env, ctx: ExecutionContext): Promise<void> {
    ctx.waitUntil(handleScheduled(env));
  },
};
