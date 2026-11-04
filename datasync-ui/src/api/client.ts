import createClient from "openapi-fetch";
import type { components, paths } from "./schema";

export type Schemas = components["schemas"];
export type Me = Schemas["Me"];
export type Role = Schemas["Role"];
export type JobView = Schemas["JobView"];
export type JobDef = Schemas["JobDef"];
export type RunView = Schemas["RunView"];
export type ConnectionView = Schemas["ConnectionView"];
export type ConnectionInput = Schemas["ConnectionInput"];
export type UserView = Schemas["UserView"];
export type AuditEntry = Schemas["Entry"];
export type Preview = Schemas["Preview"];
export type ObjectSchema = Schemas["ObjectSchema"];

/** What to do with a batch a sink still refuses after its retries (design 6). */
interface DeliveryConfig {
  retry?: { maxAttempts?: number; initialBackoff?: string; maxBackoff?: string };
  onDeadLetter?: "PAUSE" | "SKIP";
}

/** Sink entries are free-form in the API; these are the shapes the UI writes. */
export interface JdbcSinkConfig extends DeliveryConfig {
  type: "jdbc";
  connection: string;
  target: string;
  autoCreate: boolean;
  deleteMode: "SOFT" | "HARD" | "IGNORE";
  softDeleteColumn?: string;
  softDeleteFlagColumn?: string;
  /** Keep each row's hash in a _row_hash column and skip upserts that would not change the row. */
  rowHash?: boolean;
}

export interface WebhookSinkConfig extends DeliveryConfig {
  type: "webhook";
  connection: string;
  /** per-batch posts up to maxRows changes a request; per-row posts one. */
  batch?: { mode?: "per-batch" | "per-row"; maxRows?: number };
  /** upsert-with-flag sends a delete as the last known row with _is_deleted: true. */
  deleteAs?: "delete" | "upsert-with-flag";
}

export type SinkConfig = JdbcSinkConfig | WebhookSinkConfig;

export const api = createClient<paths>({ baseUrl: "" });

/** A failed call: a human message plus, sometimes, technical detail. */
export class ApiError extends Error {
  constructor(
    message: string,
    readonly status: number,
    readonly detail: string | null = null,
  ) {
    super(message);
  }
}

/** The body of a successful call, or an {@link ApiError}. */
export async function call<T>(request: Promise<{ data?: T; error?: unknown; response: Response }>): Promise<T> {
  const { data, error, response } = await request;
  if (!response.ok) {
    const body = (error ?? {}) as { message?: string; detail?: string | null };
    throw new ApiError(body.message || response.statusText || "Request failed", response.status, body.detail ?? null);
  }
  return data as T;
}
