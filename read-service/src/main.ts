import { NestFactory } from '@nestjs/core';
import { AppModule } from './app.module';

/**
 * Entry point for the Wallet CQRS POC Read Side.
 *
 * The Read Service materializes read models in PostgreSQL by consuming domain events
 * from Axon Server over HTTP (native persistent-stream integration). It does NOT embed
 * Axon Framework and never reads the Event Store to resolve business queries.
 */
async function bootstrap(): Promise<void> {
  const app = await NestFactory.create(AppModule);
  // Ensure lifecycle hooks (e.g. DatabaseModule pool teardown) run on SIGTERM/SIGINT.
  app.enableShutdownHooks();
  // Container-internal port; mapped to host 8082 by compose.yml.
  const port = process.env.PORT ?? 3000;
  await app.listen(port);
}

void bootstrap();
