import { useMutation, useQueryClient } from "@tanstack/react-query";
import { api, call, type JobView } from "@/api/client";
import { keys, useConnections } from "@/api/queries";
import { toast } from "@/components/Toaster";

/** Run, pause and resume. Failures show a toast; success shows in the job's live status. */
export function useJobActions() {
  const queryClient = useQueryClient();
  const refresh = () => {
    queryClient.invalidateQueries({ queryKey: keys.jobs });
    queryClient.invalidateQueries({ queryKey: keys.overview });
  };
  const run = useMutation({
    mutationFn: ({ job, allowDeletes = false }: { job: JobView; allowDeletes?: boolean }) =>
      call(api.POST("/api/v1/jobs/{id}/run", { params: { path: { id: job.id }, query: { allowDeletes } } })),
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

/** Connection id to name, for display. */
export function useConnectionNames() {
  const connections = useConnections().data ?? [];
  return (id: unknown) => connections.find((c) => c.id === id)?.name ?? String(id);
}
