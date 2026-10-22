/** Minimal YAML for showing plain JSON config: objects, arrays, strings, numbers, booleans and null. */
export function toYaml(value: unknown, indent = 0): string {
  const pad = "  ".repeat(indent);
  if (Array.isArray(value)) {
    if (value.length === 0) return "[]";
    return value
      .map((item) => {
        const body = toYaml(item, indent + 1);
        return isBlock(item) ? `${pad}- ${body.trimStart()}` : `${pad}- ${body}`;
      })
      .join("\n");
  }
  if (value !== null && typeof value === "object") {
    const entries = Object.entries(value);
    if (entries.length === 0) return "{}";
    return entries
      .map(([key, v]) => (isBlock(v) ? `${pad}${key}:\n${toYaml(v, indent + 1)}` : `${pad}${key}: ${toYaml(v, indent + 1)}`))
      .join("\n");
  }
  return scalar(value);
}

function isBlock(v: unknown) {
  return v !== null && typeof v === "object" && Object.keys(v).length > 0;
}

function scalar(v: unknown): string {
  if (v === null || v === undefined) return "null";
  if (typeof v !== "string") return String(v);
  const plain = /^[A-Za-z_][\w./@-]*$/.test(v) && !/^(true|false|null|yes|no|on|off|~)$/i.test(v);
  return plain ? v : JSON.stringify(v);
}
