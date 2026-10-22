import { useAudit } from "@/api/queries";
import { formatDateTime } from "@/lib/format";
import { ErrorAlert } from "./ErrorAlert";
import { Empty, Spinner, Table, Td, Th } from "./ui/layout";

/** Who changed or did what, newest first; {@code entity} narrows it to one object, e.g. "job:<id>". */
export function AuditTable({ entity }: { entity?: string }) {
  const audit = useAudit(entity);
  if (audit.isPending) return <Spinner />;
  if (audit.error) return <ErrorAlert error={audit.error} />;
  if (!audit.data.length) return <Empty title="Nothing recorded yet" />;
  return (
    <Table>
      <thead>
        <tr>
          <Th>When</Th>
          <Th>Who</Th>
          <Th>Action</Th>
          <Th>Details</Th>
        </tr>
      </thead>
      <tbody>
        {audit.data.map((e) => (
          <tr key={e.id}>
            <Td className="whitespace-nowrap">{formatDateTime(e.at)}</Td>
            <Td>{e.actor ?? "—"}</Td>
            <Td className="font-mono text-xs">{e.action}</Td>
            <Td className="text-xs text-muted-foreground">
              {Object.entries(e.detail)
                .map(([k, v]) => `${k}: ${String(v)}`)
                .join(", ")}
            </Td>
          </tr>
        ))}
      </tbody>
    </Table>
  );
}
