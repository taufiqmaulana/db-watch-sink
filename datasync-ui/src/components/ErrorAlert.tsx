import { XCircle } from "lucide-react";
import { ApiError } from "@/api/client";

/** A human message, with the technical detail behind it one click away. */
export function ErrorAlert({ error, title }: { error: unknown; title?: string }) {
  if (!error) return null;
  const message = error instanceof Error ? error.message : String(error);
  const detail = error instanceof ApiError ? error.detail : null;
  return (
    <div role="alert" className="rounded-md border border-critical/40 bg-critical/5 px-3 py-2.5 text-sm">
      <div className="flex gap-2">
        <XCircle className="mt-0.5 size-4 shrink-0 text-critical-text" aria-hidden />
        <div className="min-w-0">
          <p className="font-medium text-critical-text">{title ?? message}</p>
          {title && <p className="mt-0.5">{message}</p>}
          {detail && detail !== message && (
            <details className="mt-1">
              <summary className="cursor-pointer text-xs text-muted-foreground">Technical detail</summary>
              <pre className="mt-1 font-mono text-xs break-words whitespace-pre-wrap">{detail}</pre>
            </details>
          )}
        </div>
      </div>
    </div>
  );
}
