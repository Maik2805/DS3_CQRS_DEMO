import {
  Controller,
  ForbiddenException,
  Headers,
  HttpCode,
  HttpStatus,
  Inject,
  Logger,
  Post,
} from '@nestjs/common';

import { REBUILD_CONFIG, type RebuildConfig } from './rebuild.config';
import { RebuildService, type RebuildResult } from './rebuild.service';

/** HTTP header carrying the admin token that guards the rebuild endpoint. */
const ADMIN_TOKEN_HEADER = 'x-admin-token';

/**
 * Admin endpoint for the Read Model rebuild / replay demo (task 11.1, Req 12).
 *
 * `POST /admin/projection/rebuild` clears the PostgreSQL projection tables and resets
 * the `wallet-read-projection` persistent stream to TAIL so Axon Server re-delivers the
 * full event history, reconstructing the Read Model exclusively from replayed events.
 *
 * GUARD (simple POC condition): the request must present an `X-Admin-Token` header that
 * matches the `ADMIN_TOKEN` environment variable. This is deliberately minimal — a
 * shared-secret header, not full auth — but it prevents the destructive rebuild from
 * being triggered casually. If `ADMIN_TOKEN` is not configured the guard is OPEN and a
 * warning is logged (convenient for a local demo; set ADMIN_TOKEN to lock it down).
 */
@Controller('admin/projection')
export class AdminController {
  private readonly logger = new Logger(AdminController.name);

  constructor(
    private readonly rebuildService: RebuildService,
    @Inject(REBUILD_CONFIG) private readonly config: RebuildConfig,
  ) {}

  /**
   * Trigger a full rebuild. Returns 200 with a JSON summary once the tables are
   * cleared and the stream reset has been accepted by Axon Server. If the stream
   * reset fails, RebuildService throws and the request surfaces a 5xx (the thrown
   * message states that the tables were already truncated).
   */
  @Post('rebuild')
  @HttpCode(HttpStatus.OK)
  async rebuild(
    @Headers(ADMIN_TOKEN_HEADER) providedToken?: string,
  ): Promise<RebuildResult> {
    this.assertAuthorized(providedToken);

    this.logger.warn(
      'Rebuild requested: clearing Read Model projection tables and resetting the ' +
        'persistent stream to TAIL for full replay.',
    );

    return this.rebuildService.rebuild();
  }

  /**
   * Enforce the simple shared-secret guard. If ADMIN_TOKEN is unset the guard is open
   * (with a warning); if set, the X-Admin-Token header must match exactly.
   */
  private assertAuthorized(providedToken?: string): void {
    const expected = this.config.adminToken;

    if (!expected) {
      this.logger.warn(
        'ADMIN_TOKEN is not configured — the rebuild endpoint is UNGUARDED. ' +
          'Set ADMIN_TOKEN to require an X-Admin-Token header.',
      );
      return;
    }

    if (providedToken !== expected) {
      this.logger.warn('Rebuild rejected: missing or invalid X-Admin-Token header.');
      throw new ForbiddenException(
        'A valid X-Admin-Token header is required to trigger a projection rebuild.',
      );
    }
  }
}
