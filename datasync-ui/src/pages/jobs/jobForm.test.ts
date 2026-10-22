import { describe, expect, it } from "vitest";
import type { ObjectSchema } from "@/api/client";
import { columnRows, emptyJob, fromJobDef, newSink, toJobDef } from "./jobForm";

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

    expect(columnRows(schema, def)).toEqual(columns);
    expect(fromJobDef(def)).toMatchObject({ name: "customers", sourceConnection: "c1", sinks: [{ connection: "c2" }] });
  });
});
