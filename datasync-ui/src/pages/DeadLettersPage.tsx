import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { RotateCcw, Trash2 } from "lucide-react";
import { useState } from "react";
import { Link, useSearchParams } from "react-router-dom";
import { api, call, type Schemas } from "@/api/client";
import { keys, useAccess, useConnections, useDeadLetters, useJobs } from "@/api/queries";
import { ConfirmDialog } from "@/components/ConfirmDialog";
import { ErrorAlert } from "@/components/ErrorAlert";
import { toast } from "@/components/Toaster";
import { Button } from "@/components/ui/button";
import { Dialog } from "@/components/ui/dialog";
import { Checkbox, Field, Select } from "@/components/ui/form";
import { Card, Empty, PageHeader, Spinner, Table, Td, Th } from "@/components/ui/layout";
import { formatDateTime, formatNumber, formatRelative } from "@/lib/format";

type DeadLetter = Schemas["DeadLetterView"];
type Filter = Schemas["Filter"];

const FILTERS: Record<Filter, string> = { OPEN: "Waiting", RESOLVED: "Resolved", ALL: "All" };

/** "webhook:<connection id>" or "jdbc:<connection id>:<table>", shown with the connection's name. */
function useSinkLabel() {
  const { data: connections } = useConnections();
  return (sinkId: string) => {
    const [type, connection, ...target] = sinkId.split(":");
    const name = connections?.find((c) => c.id === connection)?.name ?? connection;
    return type === "webhook" ? `${name} (webhook)` : `${name} · ${target.join(":")}`;
  };
}

function PayloadDialog({ id, onClose }: { id: string | null; onClose: () => void }) {
  const detail = useQuery({
    queryKey: keys.deadLetter(id ?? ""),
    queryFn: () => call(api.GET("/api/v1/dead-letters/{id}", { params: { path: { id: id! } } })),
    enabled: Boolean(id),
  });
  const dl = detail.data?.deadLetter;
  return (
    <Dialog
      open={id !== null}
      onOpenChange={(o) => !o && onClose()}
      title="Dead-lettered batch"
      description={dl && `${formatNumber(dl.changeCount)} changes from ${dl.jobName ?? "a deleted job"}, batch ${dl.batchId}`}
      wide
    >
      <div className="grid gap-3 pb-5">
        <ErrorAlert error={detail.error} />
        {dl?.error && (
          <p className="rounded-md bg-critical/10 p-3 text-sm break-words text-critical-text">
            {dl.error}
            <span className="mt-1 block text-xs text-muted-foreground">
              After {dl.attempts} attempt{dl.attempts === 1 ? "" : "s"}
            </span>
          </p>
        )}
        {detail.isPending ? (
          <Spinner />
        ) : (
          detail.data && (
            <pre className="max-h-[50vh] overflow-auto rounded-md bg-muted p-3 font-mono text-xs">
              {JSON.stringify(
                (detail.data.payload.changes as { op: string; key: unknown; row?: unknown }[]).map(({ op, key, row }) => ({
                  op: op.toLowerCase(),
                  key,
                  ...(row ? { row } : {}),
                })),
                null,
                2,
              )}
            </pre>
          )
        )}
      </div>
    </Dialog>
  );
}

export function DeadLettersPage() {
  const [params, setParams] = useSearchParams();
  const jobId = params.get("job") ?? "";
  const status = (params.get("status") as Filter | null) ?? "OPEN";
  const deadLetters = useDeadLetters(jobId, status);
  const jobs = useJobs();
  const { canOperate } = useAccess();
  const sinkLabel = useSinkLabel();
  const queryClient = useQueryClient();
  const [selected, setSelected] = useState<Set<string>>(new Set());
  const [viewing, setViewing] = useState<string | null>(null);
  const [discarding, setDiscarding] = useState<string[] | null>(null);

  const setFilter = (key: string, value: string) => {
    const next = new URLSearchParams(params);
    if (value) next.set(key, value);
    else next.delete(key);
    setParams(next, { replace: true });
    setSelected(new Set());
  };
  const refresh = () => {
    queryClient.invalidateQueries({ queryKey: ["dead-letters"] });
    queryClient.invalidateQueries({ queryKey: keys.overview });
    setSelected(new Set());
  };
  const retry = useMutation({
    mutationFn: (ids: string[]) => call(api.POST("/api/v1/dead-letters/retry", { body: { ids } })),
    onSuccess: (result) => {
      refresh();
      const failed = Object.values(result.failed);
      if (result.resolved) toast.success(`Delivered ${result.resolved} batch${result.resolved === 1 ? "" : "es"}.`);
      if (failed.length) toast.error(`${failed.length} still failed: ${failed[0]}`);
    },
    onError: (e) => toast.error(e),
  });
  const discard = useMutation({
    mutationFn: (ids: string[]) => call(api.POST("/api/v1/dead-letters/discard", { body: { ids } })),
    onSuccess: (result) => {
      refresh();
      setDiscarding(null);
      toast.success(`Discarded ${result.resolved} batch${result.resolved === 1 ? "" : "es"}.`);
    },
    onError: (e) => {
      setDiscarding(null);
      toast.error(e);
    },
  });

  const rows = deadLetters.data ?? [];
  const open = rows.filter((d) => !d.resolvedAt);
  const allSelected = open.length > 0 && open.every((d) => selected.has(d.id));
  const toggle = (id: string) =>
    setSelected((s) => {
      const next = new Set(s);
      if (!next.delete(id)) next.add(id);
      return next;
    });
  const ids = [...selected];

  return (
    <>
      <PageHeader
        title="Dead letters"
        description="Batches a sink refused after all its retries. Retry them once the sink is fixed, or discard them."
        actions={
          canOperate &&
          ids.length > 0 && (
            <>
              <Button variant="outline" loading={retry.isPending} onClick={() => retry.mutate(ids)}>
                <RotateCcw aria-hidden /> Retry {ids.length}
              </Button>
              <Button variant="destructive-outline" onClick={() => setDiscarding(ids)}>
                <Trash2 aria-hidden /> Discard {ids.length}
              </Button>
            </>
          )
        }
      />
      <div className="mb-4 flex flex-wrap gap-4">
        <Field label="Job" className="w-60">
          {(id) => (
            <Select id={id} value={jobId} onChange={(e) => setFilter("job", e.target.value)}>
              <option value="">All jobs</option>
              {jobs.data?.map((j) => (
                <option key={j.id} value={j.id}>
                  {j.name}
                </option>
              ))}
            </Select>
          )}
        </Field>
        <Field label="Show" className="w-40">
          {(id) => (
            <Select id={id} value={status} onChange={(e) => setFilter("status", e.target.value === "OPEN" ? "" : e.target.value)}>
              {Object.entries(FILTERS).map(([value, label]) => (
                <option key={value} value={value}>
                  {label}
                </option>
              ))}
            </Select>
          )}
        </Field>
      </div>
      <ErrorAlert error={deadLetters.error} />
      <Card>
        {deadLetters.isPending ? (
          <Spinner />
        ) : !rows.length ? (
          <Empty title={status === "OPEN" ? "Nothing waiting" : "No dead letters"}>
            {status === "OPEN" && "Every batch reached its sink."}
          </Empty>
        ) : (
          <Table>
            <thead>
              <tr>
                {canOperate && (
                  <Th className="w-0">
                    <Checkbox
                      aria-label="Select all waiting"
                      checked={allSelected}
                      disabled={!open.length}
                      onChange={() => setSelected(allSelected ? new Set() : new Set(open.map((d) => d.id)))}
                    />
                  </Th>
                )}
                <Th>When</Th>
                <Th>Job</Th>
                <Th>Sink</Th>
                <Th className="text-right">Changes</Th>
                <Th>Error</Th>
                <Th>Status</Th>
                <Th className="w-0" />
              </tr>
            </thead>
            <tbody>
              {rows.map((d: DeadLetter) => (
                <tr key={d.id} className="hover:bg-muted/50">
                  {canOperate && (
                    <Td>
                      <Checkbox
                        aria-label={`Select batch ${d.batchId} of ${d.jobName ?? "deleted job"}`}
                        checked={selected.has(d.id)}
                        disabled={Boolean(d.resolvedAt)}
                        onChange={() => toggle(d.id)}
                      />
                    </Td>
                  )}
                  <Td className="whitespace-nowrap" title={formatDateTime(d.createdAt)}>
                    {formatRelative(d.createdAt)}
                  </Td>
                  <Td>
                    {d.jobName ? (
                      <Link to={`/jobs/${d.jobId}`} className="font-medium hover:underline">
                        {d.jobName}
                      </Link>
                    ) : (
                      <span className="text-muted-foreground">Deleted job</span>
                    )}
                  </Td>
                  <Td className="text-sm">{sinkLabel(d.sinkId)}</Td>
                  <Td className="text-right tabular-nums">{formatNumber(d.changeCount)}</Td>
                  <Td className="max-w-sm">
                    <span className="line-clamp-2 text-xs break-words whitespace-normal text-critical-text" title={d.error ?? undefined}>
                      {d.error}
                    </span>
                  </Td>
                  <Td className="whitespace-nowrap text-sm">
                    {d.resolution === "RETRIED" ? "Delivered" : d.resolution === "DISCARDED" ? "Discarded" : `Waiting, ${d.attempts} attempts`}
                  </Td>
                  <Td>
                    <div className="flex justify-end gap-1">
                      <Button size="sm" variant="ghost" onClick={() => setViewing(d.id)}>
                        View
                      </Button>
                      {canOperate && !d.resolvedAt && (
                        <Button size="sm" variant="outline" loading={retry.isPending && retry.variables?.includes(d.id)} onClick={() => retry.mutate([d.id])}>
                          Retry
                        </Button>
                      )}
                    </div>
                  </Td>
                </tr>
              ))}
            </tbody>
          </Table>
        )}
      </Card>
      <PayloadDialog id={viewing} onClose={() => setViewing(null)} />
      <ConfirmDialog
        open={discarding !== null}
        onOpenChange={(o) => !o && setDiscarding(null)}
        title={`Discard ${discarding?.length ?? 0} batch${discarding?.length === 1 ? "" : "es"}?`}
        effect="The changes in them will never reach their sink, unless a later reconcile sends those rows again."
        confirmLabel="Discard"
        destructive
        loading={discard.isPending}
        onConfirm={() => discarding && discard.mutate(discarding)}
      />
    </>
  );
}
