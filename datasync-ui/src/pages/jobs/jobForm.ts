import { z } from "zod";
import type { JdbcSinkConfig, JobDef, ObjectSchema, Schemas } from "@/api/client";

const DELETE_MODES = ["SOFT", "HARD", "IGNORE"] as const;

const count = (min: number, max: number) =>
  z.number({ error: "Enter a number" }).int("Enter a whole number").min(min, `At least ${min}`).max(max, `At most ${max}`);

const columnSchema = z.object({
  name: z.string(),
  type: z.string(),
  kind: z.string(),
  include: z.boolean(),
  key: z.boolean(),
  rename: z.string(),
});

const sinkSchema = z.object({
  connection: z.string().min(1, "Choose a connection"),
  target: z.string().trim().min(1, "Target table is required"),
  autoCreate: z.boolean(),
  deleteMode: z.enum(DELETE_MODES),
  softDeleteColumn: z.string().trim().min(1, "Required"),
  softDeleteFlagColumn: z.string().trim().min(1, "Required"),
});

const CRON_HINT = "Use five fields: minute hour day month weekday";
const isCron = (s: string) => /^\S+(\s+\S+){4}$/.test(s.trim());

export const jobSchema = z.object({
  name: z.string().trim().min(1, "Name is required"),
  sourceConnection: z.string().min(1, "Choose a source connection"),
  sourceObject: z.string().trim().min(1, "Choose a table or view"),
  /** "connection|object" the columns were loaded for. */
  columnsFor: z.string(),
  columns: z
    .array(columnSchema)
    .min(1, "Load the source columns first")
    .refine((cols) => cols.some((c) => c.key), "Pick at least one key column"),
  batchSize: count(1, 100_000),
  fetchSize: count(1, 100_000),
  queryTimeoutSeconds: count(1, 86_400),
  maxDeletePercent: count(0, 100),
  sinks: z.array(sinkSchema).min(1, "Add at least one sink"),
  scheduleKind: z.enum(["interval", "cron"]),
  every: z.string().refine((s) => !s.trim() || (intervalSeconds(s) ?? 0) >= 10, "Use a number with s, m or h, at least 10s"),
  cron: z.string().refine((s) => !s.trim() || isCron(s), CRON_HINT),
  fixedDelay: z.boolean(),
  jitterPercent: count(0, 50),
  mode: z.enum(["SNAPSHOT_DIFF", "WATERMARK", "HYBRID"]),
  watermarkColumn: z.string(),
  overlap: z.string().refine((s) => !s.trim() || intervalSeconds(s) !== null, "Use a number with s, m or h"),
  reconcileCron: z.string().refine((s) => !s.trim() || isCron(s), CRON_HINT),
});

/** Column types a watermark can be read from: an update time or an increasing number. */
export const WATERMARK_KINDS = ["DATE", "TIMESTAMP", "TIMESTAMP_TZ", "INT32", "INT64", "DECIMAL"];

/** What the chosen mode still needs; checked by the wizard because the requirement depends on the mode. */
export function modeProblems(v: Pick<JobFormValues, "mode" | "watermarkColumn" | "reconcileCron">) {
  const problems: { field: "watermarkColumn" | "reconcileCron"; message: string }[] = [];
  if (v.mode !== "SNAPSHOT_DIFF" && !v.watermarkColumn) problems.push({ field: "watermarkColumn", message: "Choose a watermark column" });
  if (v.mode === "HYBRID" && !v.reconcileCron.trim()) problems.push({ field: "reconcileCron", message: "Enter when to reconcile" });
  return problems;
}

/** "Snapshot diff", "Watermark on UPDATED_AT", "Hybrid: watermark on UPDATED_AT, reconcile 0 2 * * *". */
export function describeMode(def: Pick<JobDef, "mode" | "watermark" | "reconcile">) {
  if (def.mode === "SNAPSHOT_DIFF") return "Snapshot diff";
  const watermark = `watermark on ${def.watermark.column}, ${def.watermark.overlap} overlap`;
  return def.mode === "WATERMARK" ? `W${watermark.slice(1)}` : `Hybrid: ${watermark}, reconcile ${def.reconcile.cron}`;
}

/** Seconds in an interval such as "30s", "5m" or "2h", or null when it is not one. */
export function intervalSeconds(text: string) {
  const m = /^(\d+)\s*([smh])$/i.exec(text.trim());
  if (!m) return null;
  return Number(m[1]) * { s: 1, m: 60, h: 3600 }[m[2].toLowerCase() as "s" | "m" | "h"];
}

/** "Every 5m", "Every 30s after the previous run ends", or "Cron 0 2 * * *". */
export function describeSchedule(def: Pick<JobDef, "every" | "schedule" | "fixedDelay">) {
  if (!def.every) return `Cron ${def.schedule}`;
  return def.fixedDelay ? `Every ${def.every} after the previous run ends` : `Every ${def.every}`;
}

export type JobFormValues = z.infer<typeof jobSchema>;
export type ColumnRow = JobFormValues["columns"][number];
export type SinkValues = JobFormValues["sinks"][number];

export const newSink = (target = ""): SinkValues => ({
  connection: "",
  target,
  autoCreate: true,
  deleteMode: "SOFT",
  softDeleteColumn: "_deleted_at",
  softDeleteFlagColumn: "_is_deleted",
});

export const emptyJob: JobFormValues = {
  name: "",
  sourceConnection: "",
  sourceObject: "",
  columnsFor: "",
  columns: [],
  batchSize: 10_000,
  fetchSize: 5_000,
  queryTimeoutSeconds: 300,
  maxDeletePercent: 20,
  sinks: [newSink()],
  scheduleKind: "interval",
  every: "5m",
  cron: "*/5 * * * *",
  fixedDelay: false,
  jitterPercent: 10,
  mode: "SNAPSHOT_DIFF",
  watermarkColumn: "",
  overlap: "5m",
  reconcileCron: "0 2 * * *",
};

export function typeLabel(t: Schemas["CanonicalType"]) {
  if (t.kind === "DECIMAL") return t.precision ? `DECIMAL(${t.precision},${t.scale})` : "DECIMAL";
  if (t.kind === "TIMESTAMP") return `TIMESTAMP(${t.precision})`;
  return t.kind;
}

/** Key columns are always included. With "*", BYTES columns are only included when named. */
const selected = (c: ColumnRow) => c.include || c.key;

export function toJobDef(v: JobFormValues): JobDef {
  const sinks: JdbcSinkConfig[] = v.sinks.map((s) => ({ type: "jdbc", ...s, target: s.target.trim() }));
  return {
    name: v.name.trim(),
    source: { connection: v.sourceConnection, object: v.sourceObject.trim() },
    keys: v.columns.filter((c) => c.key).map((c) => c.name),
    columns: {
      include: ["*", ...v.columns.filter((c) => c.kind === "BYTES" && selected(c)).map((c) => c.name)],
      exclude: v.columns.filter((c) => c.kind !== "BYTES" && !selected(c)).map((c) => c.name),
      rename: Object.fromEntries(
        v.columns.filter((c) => selected(c) && c.rename.trim() && c.rename.trim() !== c.name).map((c) => [c.name, c.rename.trim()]),
      ),
    },
    mode: v.mode,
    // an empty column or cron means "none"; the fields keep their values in the form while another mode is chosen
    watermark: { column: v.mode === "SNAPSHOT_DIFF" ? "" : v.watermarkColumn, overlap: v.overlap.trim() || emptyJob.overlap },
    reconcile: { cron: v.mode === "HYBRID" ? v.reconcileCron.trim() : "" },
    batchSize: v.batchSize,
    fetchSize: v.fetchSize,
    queryTimeoutSeconds: v.queryTimeoutSeconds,
    maxDeletePercent: v.maxDeletePercent,
    sinks: sinks as unknown as JobDef["sinks"],
    // an empty interval means "use the cron expression"
    schedule: v.cron.trim() || emptyJob.cron,
    every: v.scheduleKind === "interval" ? v.every.trim() : "",
    fixedDelay: v.scheduleKind === "interval" && v.fixedDelay,
    jitterPercent: v.jitterPercent,
  };
}

/** Form values for a stored job. Columns are filled in once the source schema is loaded ({@link columnRows}). */
export function fromJobDef(def: JobDef): JobFormValues {
  // keep only the form's fields, filling the ones an older or hand-written config left out
  const sinks = (def.sinks as unknown as Partial<SinkValues>[]).map((s): SinkValues => {
    const d = { ...newSink(), ...s };
    return {
      connection: d.connection,
      target: d.target,
      autoCreate: d.autoCreate,
      deleteMode: d.deleteMode,
      softDeleteColumn: d.softDeleteColumn,
      softDeleteFlagColumn: d.softDeleteFlagColumn,
    };
  });
  return {
    ...emptyJob,
    name: def.name,
    sourceConnection: def.source.connection,
    sourceObject: def.source.object,
    batchSize: def.batchSize,
    fetchSize: def.fetchSize,
    queryTimeoutSeconds: def.queryTimeoutSeconds,
    maxDeletePercent: def.maxDeletePercent,
    sinks,
    mode: def.mode,
    watermarkColumn: def.watermark.column ?? "",
    overlap: def.watermark.overlap,
    reconcileCron: def.reconcile.cron || emptyJob.reconcileCron,
    scheduleKind: def.every ? "interval" : "cron",
    every: def.every || emptyJob.every,
    cron: def.schedule,
    fixedDelay: def.fixedDelay,
    jitterPercent: def.jitterPercent,
  };
}

/** One row per source column, selected as in {@code def} when given, else all but BYTES with the primary key as key. */
export function columnRows(schema: ObjectSchema, def?: JobDef): ColumnRow[] {
  return schema.columns.map((col) => {
    const kind = col.type.kind;
    const cfg = def?.columns;
    const include = !cfg
      ? kind !== "BYTES"
      : cfg.include.includes("*")
        ? (kind !== "BYTES" || cfg.include.includes(col.name)) && !cfg.exclude.includes(col.name)
        : cfg.include.includes(col.name);
    const key = def?.keys.length ? def.keys.includes(col.name) : schema.keyColumns.includes(col.name);
    return { name: col.name, type: typeLabel(col.type), kind, include: include || key, key, rename: cfg?.rename[col.name] ?? "" };
  });
}
