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

/** Sink entries are free-form in the API; this is the shape the UI writes for database sinks. */
export interface JdbcSinkConfig {
  type: "jdbc";
  connection: string;
  target: string;
  autoCreate: boolean;
  deleteMode: "SOFT" | "HARD" | "IGNORE";
  softDeleteColumn?: string;
  softDeleteFlagColumn?: string;
}

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
