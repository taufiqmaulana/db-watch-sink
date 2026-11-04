import { useMutation, useQueryClient } from "@tanstack/react-query";
import { AlertTriangle, CheckCircle2, Loader2, PauseCircle, RefreshCw } from "lucide-react";
import { useState } from "react";
import { Link } from "react-router-dom";
import { api, call, type JobView, type Schemas } from "@/api/client";
import { keys, useAccess } from "@/api/queries";
import { ConfirmDialog } from "@/components/ConfirmDialog";
import { toast } from "@/components/Toaster";
import { Button } from "@/components/ui/button";
import { Card, CardHeader, Table, Td, Th } from "@/components/ui/layout";
import { cn, formatNumber, formatRelative } from "@/lib/format";

type SinkView = Schemas["SinkView"];

/** Status is an icon plus a label, never color alone. */
function SinkStatus({ sink }: { sink: SinkView }) {
  const [Icon, label, tone, spin] =
    sink.status === "PAUSED"
      ? [PauseCircle, "Paused", "text-warning-text", false]
      : sink.status === "RESNAPSHOT_REQUIRED"
        ? [AlertTriangle, "Needs resnapshot", "text-critical-text", false]
        : sink.delivering
          ? [Loader2, "Delivering", "text-primary", true]
          : sink.attempts > 0
            ? [RefreshCw, "Retrying", "text-warning-text", false]
            : [CheckCircle2, sink.backlog ? "Waiting" : "Up to date", "text-good-text", false];
  return (
    <span className={cn("inline-flex items-center gap-1.5 text-sm font-medium whitespace-nowrap", tone)}>
      <Icon className={cn("size-4", spin && "animate-spin")} aria-hidden />
      {label}
    </span>
  );
}

/**
 * Each sink delivers the job's changes on its own (design 6), so one that is slow, retrying or paused holds up no other.
 * Operators can resume a paused sink or resnapshot one, which reloads it with every row.
 */
export function SinksPanel({ job }: { job: JobView }) {
  const { canOperate } = useAccess();
  const queryClient = useQueryClient();
  const [resnapshotting, setResnapshotting] = useState<SinkView | null>(null);
  const refresh = () => {
    queryClient.invalidateQueries({ queryKey: keys.job(job.id) });
    queryClient.invalidateQueries({ queryKey: keys.jobs });
  };
  const resume = useMutation({
    mutationFn: (sink: SinkView) => call(api.POST("/api/v1/jobs/{id}/resume-sink", { params: { path: { id: job.id }, query: { sink: sink.id } } })),
    onSuccess: (_, sink) => {
      refresh();
      toast.success(`Resumed ${sink.label}.`);
    },
    onError: (e) => toast.error(e),
  });
  const resnapshot = useMutation({
    mutationFn: (sink: SinkView) => call(api.POST("/api/v1/jobs/{id}/resnapshot", { params: { path: { id: job.id }, query: { sink: sink.id } } })),
    onSuccess: (_, sink) => {
      setResnapshotting(null);
      refresh();
      toast.success(`Resnapshot of ${sink.label} started.`);
    },
    onError: (e) => {
      setResnapshotting(null);
      toast.error(e);
    },
  });

  return (
    <Card>
      <CardHeader
        title="Sinks"
        description="Each sink receives the job's changes from a queue on its own, so a slow or failing sink does not hold up the others."
      />
      <Table>
        <thead>
          <tr>
            <Th>Sink</Th>
            <Th>Status</Th>
            <Th className="text-right">Waiting</Th>
            <Th>Last delivery</Th>
            <Th>Problem</Th>
            {canOperate && <Th className="w-0" />}
          </tr>
        </thead>
        <tbody>
          {job.sinks.map((s) => (
            <tr key={s.id} className="hover:bg-muted/50">
              <Td>
                <p className="font-medium">{s.label}</p>
                {!s.loaded && <p className="text-xs text-muted-foreground">Full load on the next run</p>}
              </Td>
              <Td>
                <SinkStatus sink={s} />
                {s.attempts > 0 && s.nextRetryAt && (
                  <p className="text-xs text-muted-foreground">
                    {s.attempts} failed, next try {formatRelative(s.nextRetryAt)}
                  </p>
                )}
              </Td>
              <Td className="text-right tabular-nums">
                {formatNumber(s.backlog)} batch{s.backlog === 1 ? "" : "es"}
              </Td>
              <Td className="whitespace-nowrap text-muted-foreground">{s.deliveredAt ? formatRelative(s.deliveredAt) : "—"}</Td>
              <Td className="max-w-sm">
                {s.lastError ? (
                  <span className="line-clamp-3 text-xs break-words whitespace-normal text-critical-text" title={s.lastError}>
                    {s.lastError}
                  </span>
                ) : (
                  "—"
                )}
                {s.status === "PAUSED" && (
                  <Link to={`/dead-letters?job=${job.id}`} className="mt-1 block text-xs text-primary hover:underline">
                    See the dead letters
                  </Link>
                )}
              </Td>
              {canOperate && (
                <Td>
                  <div className="flex justify-end gap-1">
                    {s.status === "PAUSED" && (
                      <Button size="sm" variant="outline" loading={resume.isPending && resume.variables?.id === s.id} onClick={() => resume.mutate(s)}>
                        Resume
                      </Button>
                    )}
                    <Button
                      size="sm"
                      variant={s.status === "RESNAPSHOT_REQUIRED" ? "primary" : "ghost"}
                      disabled={job.running}
                      onClick={() => setResnapshotting(s)}
                    >
                      Resnapshot
                    </Button>
                  </div>
                </Td>
              )}
            </tr>
          ))}
        </tbody>
      </Table>
      <ConfirmDialog
        open={resnapshotting !== null}
        onOpenChange={(o) => !o && setResnapshotting(null)}
        title={`Resnapshot ${resnapshotting?.label}?`}
        effect="Its waiting changes are dropped and a run starts that sends it every source row again. Other sinks only get that run's changes. On a large source this reads the whole table."
        confirmLabel="Resnapshot"
        loading={resnapshot.isPending}
        onConfirm={() => resnapshotting && resnapshot.mutate(resnapshotting)}
      />
    </Card>
  );
}
