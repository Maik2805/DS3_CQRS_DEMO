import {
  Controller,
  Get,
  Inject,
  Logger,
  ServiceUnavailableException,
} from '@nestjs/common';
import { sql } from 'drizzle-orm';

import {
  DRIZZLE,
  type WalletReadDatabase,
} from '../database/database.tokens';

/** Liveness payload shape. */
interface LivenessResponse {
  status: 'ok';
}

/** Readiness payload shape (adds the dependency check result). */
interface ReadinessResponse {
  status: 'ok';
  database: 'up';
}

/**
 * Read Service health endpoints (task 10.2, Req 16.4).
 *
 * `GET /health` is a fast, dependency-free LIVENESS probe: it always returns 200 with
 * `{ status: 'ok' }` as long as the process can serve HTTP. This is the endpoint the
 * compose.yml healthcheck and the Axon Server integration health checks poll, so it must
 * stay cheap and must not fail just because PostgreSQL is momentarily unavailable.
 *
 * `GET /health/ready` is an optional READINESS probe that additionally runs a lightweight
 * `SELECT 1` against the Read Model. If the database is unreachable it returns HTTP 503
 * (ServiceUnavailableException) so orchestration can distinguish "process up" from
 * "process up and able to serve queries".
 */
@Controller('health')
export class HealthController {
  private readonly logger = new Logger(HealthController.name);

  constructor(
    @Inject(DRIZZLE) private readonly db: WalletReadDatabase,
  ) {}

  /** GET /health — liveness. Always 200; never touches the database. */
  @Get()
  liveness(): LivenessResponse {
    return { status: 'ok' };
  }

  /**
   * GET /health/ready — readiness. Runs `SELECT 1`; 200 when the DB answers, 503 otherwise.
   */
  @Get('ready')
  async readiness(): Promise<ReadinessResponse> {
    try {
      await this.db.execute(sql`SELECT 1`);
      return { status: 'ok', database: 'up' };
    } catch (error) {
      const message = error instanceof Error ? error.message : String(error);
      this.logger.error(`Readiness check failed: database unreachable: ${message}`);
      throw new ServiceUnavailableException({
        status: 'error',
        database: 'down',
      });
    }
  }
}
