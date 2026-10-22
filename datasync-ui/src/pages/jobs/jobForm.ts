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
});

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
    mode: "SNAPSHOT_DIFF",
    batchSize: v.batchSize,
    fetchSize: v.fetchSize,
    queryTimeoutSeconds: v.queryTimeoutSeconds,
    maxDeletePercent: v.maxDeletePercent,
    sinks: sinks as unknown as JobDef["sinks"],
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
