import { describe, expect, it } from "vitest";
import { toYaml } from "./yaml";

describe("toYaml", () => {
  it("writes nested objects, lists of objects, and quotes strings that need it", () => {
    expect(
      toYaml({
        name: "customers_sync",
        keys: ["ID"],
        columns: { include: ["*"], rename: {} },
        sinks: [{ type: "jdbc", target: "public.customers", autoCreate: true }],
        note: "a: b",
      }),
    ).toBe(
      [
        "name: customers_sync",
        "keys:",
        "  - ID",
        "columns:",
        "  include:",
        '    - "*"',
        "  rename: {}",
        "sinks:",
        "  - type: jdbc",
        "    target: public.customers",
        "    autoCreate: true",
        'note: "a: b"',
      ].join("\n"),
    );
  });
});
