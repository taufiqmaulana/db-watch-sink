import { useQueryClient } from "@tanstack/react-query";
import { useEffect } from "react";
import { keys } from "./queries";

/** Refreshes jobs, runs and the dashboard when the server reports a run or job change (SSE). */
export function useLiveUpdates() {
  const queryClient = useQueryClient();
  useEffect(() => {
    const source = new EventSource("/api/v1/events");
    source.onmessage = (message) => {
      const event = JSON.parse(message.data) as { type: string };
      if (event.type === "ping") return;
      queryClient.invalidateQueries({ queryKey: keys.jobs });
      queryClient.invalidateQueries({ queryKey: keys.overview });
      queryClient.invalidateQueries({ queryKey: ["audit"] });
    };
    return () => source.close();
  }, [queryClient]);
}
