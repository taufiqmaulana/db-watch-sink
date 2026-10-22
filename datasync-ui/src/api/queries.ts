import { useQuery } from "@tanstack/react-query";
import { api, ApiError, call, type Me } from "./client";

export const keys = {
  me: ["me"] as const,
  overview: ["overview"] as const,
  jobs: ["jobs"] as const,
  job: (id: string) => ["jobs", id] as const,
  runs: (id: string) => ["jobs", id, "runs"] as const,
  connections: ["connections"] as const,
  objects: (id: string, schema: string) => ["connections", id, "objects", schema] as const,
  objectSchema: (id: string, name: string) => ["connections", id, "schema", name] as const,
  users: ["users"] as const,
  audit: (entity?: string) => ["audit", entity ?? "all"] as const,
};

/** The signed-in user, or null when signed out. */
export function useMe() {
  return useQuery({
    queryKey: keys.me,
    queryFn: async (): Promise<Me | null> => {
      try {
        return await call(api.GET("/api/v1/auth/me"));
      } catch (e) {
        if (e instanceof ApiError && e.status === 401) return null;
        throw e;
      }
    },
    staleTime: Infinity,
  });
}

/** What the signed-in user may do. */
export function useAccess() {
  const role = useMe().data?.role;
  return { canOperate: role === "OPERATOR" || role === "ADMIN", canAdmin: role === "ADMIN" };
}

export const useOverview = () => useQuery({ queryKey: keys.overview, queryFn: () => call(api.GET("/api/v1/overview")) });

export const useJobs = () => useQuery({ queryKey: keys.jobs, queryFn: () => call(api.GET("/api/v1/jobs")) });

export const useJob = (id: string | undefined) =>
  useQuery({
    queryKey: keys.job(id ?? ""),
    queryFn: () => call(api.GET("/api/v1/jobs/{id}", { params: { path: { id: id! } } })),
    enabled: Boolean(id),
  });

export const useRuns = (id: string) =>
  useQuery({
    queryKey: keys.runs(id),
    queryFn: () => call(api.GET("/api/v1/jobs/{id}/runs", { params: { path: { id }, query: { limit: 100 } } })),
  });

export const useConnections = () =>
  useQuery({ queryKey: keys.connections, queryFn: () => call(api.GET("/api/v1/connections")) });

export const useUsers = (enabled = true) =>
  useQuery({ queryKey: keys.users, queryFn: () => call(api.GET("/api/v1/users")), enabled });

export const useAudit = (entity?: string) =>
  useQuery({
    queryKey: keys.audit(entity),
    queryFn: () => call(api.GET("/api/v1/audit", { params: { query: { entity, limit: 200 } } })),
  });

export const useObjects = (connectionId: string, schema: string, enabled: boolean) =>
  useQuery({
    queryKey: keys.objects(connectionId, schema),
    queryFn: () => call(api.GET("/api/v1/connections/{id}/objects", { params: { path: { id: connectionId }, query: { schema } } })),
    enabled,
    staleTime: 60_000,
  });

export const useObjectSchema = (connectionId: string, name: string) =>
  useQuery({
    queryKey: keys.objectSchema(connectionId, name),
    queryFn: () =>
      call(api.GET("/api/v1/connections/{id}/objects/{name}/schema", { params: { path: { id: connectionId, name } } })),
    enabled: Boolean(connectionId && name),
    staleTime: 60_000,
    retry: false,
  });
