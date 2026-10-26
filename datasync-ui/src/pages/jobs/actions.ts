import { useMutation, useQueryClient } from "@tanstack/react-query";
import { api, call, type JobView } from "@/api/client";
import { keys, useConnections } from "@/api/queries";
import { toast } from "@/components/Toaster";
import { formatRelative } from "@/lib/format";

/** Run, pause and resume. Failures show a toast; success shows in the job's live status. */
export function useJobActions() {
  const queryClient = useQueryClient();
  const refresh = () => {
    queryClient.invalidateQueries({ queryKey: keys.jobs });
    queryClient.invalidateQueries({ queryKey: keys.overview });
  };
  /** A regular run, or with {@code reconcile} a full comparison (the only run that applies deletes in watermark modes). */
  const run = useMutation({
    mutationFn: ({ job, allowDeletes = false, reconcile = false }: { job: JobView; allowDeletes?: boolean; reconcile?: boolean }) =>
      reconcile
        ? call(api.POST("/api/v1/jobs/{id}/reconcile", { params: { path: { id: job.id }, query: { allowDeletes } } }))
        : call(api.POST("/api/v1/jobs/{id}/run", { params: { path: { id: job.id }, query: { allowDeletes } } })),
    onSuccess: refresh,
    onError: toast.error,
  });
  const setPaused = useMutation({
    mutationFn: ({ job, paused }: { job: JobView; paused: boolean }) =>
      paused
        ? call(api.POST("/api/v1/jobs/{id}/pause", { params: { path: { id: job.id } } }))
        : call(api.POST("/api/v1/jobs/{id}/resume", { params: { path: { id: job.id } } })),
    onSuccess: refresh,
    onError: toast.error,
  });
  return { run, setPaused };
}

/** "in 4 minutes", or why the job will not run on its own. */
export function nextRunLabel(job: JobView) {
  if (job.nextRunAt) return formatRelative(job.nextRunAt);
  if (job.status === "PAUSED") return "Paused";
  if (job.status === "NEEDS_REVIEW") return "After review";
  return "—";
}

/** Connection id to name, for display. */
export function useConnectionNames() {
  const connections = useConnections().data ?? [];
  return (id: unknown) => connections.find((c) => c.id === id)?.name ?? String(id);
}
