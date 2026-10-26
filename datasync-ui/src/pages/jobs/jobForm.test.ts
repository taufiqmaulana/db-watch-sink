import { describe, expect, it } from "vitest";
import type { ObjectSchema } from "@/api/client";
import { columnRows, describeSchedule, emptyJob, fromJobDef, intervalSeconds, newSink, toJobDef } from "./jobForm";

const schema: ObjectSchema = {
  ref: { schema: "HR", name: "CUSTOMERS" },
  keyColumns: ["ID"],
  columns: [
    { name: "ID", type: { kind: "INT64", precision: 0, scale: 0 }, nullable: false },
    { name: "NAME", type: { kind: "STRING", precision: 0, scale: 0 }, nullable: true },
    { name: "NOTE", type: { kind: "STRING", precision: 0, scale: 0 }, nullable: true },
    { name: "PHOTO", type: { kind: "BYTES", precision: 0, scale: 0 }, nullable: true },
  ],
};

describe("job form", () => {
  it("round-trips a cron schedule and drops the interval it no longer uses", () => {
    const base = { ...emptyJob, name: "j", sourceConnection: "c1", sourceObject: "T", columns: columnRows(schema) };
    const def = toJobDef({ ...base, scheduleKind: "cron", cron: "0 2 * * *", fixedDelay: true });
    expect(def).toMatchObject({ schedule: "0 2 * * *", every: "", fixedDelay: false });
    expect(fromJobDef(def)).toMatchObject({ scheduleKind: "cron", cron: "0 2 * * *", every: "5m" });
    expect(describeSchedule(def)).toBe("Cron 0 2 * * *");
    expect(intervalSeconds("2h")).toBe(7200);
    expect(intervalSeconds("soon")).toBeNull();
  });

  it("defaults to all non-binary columns with the primary key as key", () => {
    const rows = columnRows(schema);
    expect(rows.map((r) => [r.name, r.include, r.key])).toEqual([
      ["ID", true, true],
      ["NAME", true, false],
      ["NOTE", true, false],
      ["PHOTO", false, false],
    ]);
  });

  it("writes exclusions, named binary columns and renames, and reads them back", () => {
    const columns = columnRows(schema).map((r) =>
      r.name === "NOTE" ? { ...r, include: false } : r.name === "PHOTO" ? { ...r, include: true } : r.name === "NAME" ? { ...r, rename: "customer_name" } : r,
    );
    const def = toJobDef({
      ...emptyJob,
      name: " customers ",
      sourceConnection: "c1",
      sourceObject: "HR.CUSTOMERS",
      columns,
      sinks: [{ ...newSink("public.customers"), connection: "c2" }],
    });

    expect(def.name).toBe("customers");
    expect(def.keys).toEqual(["ID"]);
    expect(def.columns).toEqual({ include: ["*", "PHOTO"], exclude: ["NOTE"], rename: { NAME: "customer_name" } });
    expect(def.sinks[0]).toMatchObject({ type: "jdbc", connection: "c2", target: "public.customers", deleteMode: "SOFT" });

    expect(def).toMatchObject({ every: "5m", schedule: "*/5 * * * *", fixedDelay: false, jitterPercent: 10 });

    expect(columnRows(schema, def)).toEqual(columns);
    expect(fromJobDef(def)).toMatchObject({ name: "customers", sourceConnection: "c1", sinks: [{ connection: "c2" }] });
  });
});
