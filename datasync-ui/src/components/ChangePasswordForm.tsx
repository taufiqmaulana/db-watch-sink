import { zodResolver } from "@hookform/resolvers/zod";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { useForm } from "react-hook-form";
import { z } from "zod";
import { api, call } from "@/api/client";
import { keys } from "@/api/queries";
import { ErrorAlert } from "./ErrorAlert";
import { Button } from "./ui/button";
import { Field, Input } from "./ui/form";

const MIN_LENGTH = 10;

const schema = z
  .object({
    currentPassword: z.string().min(1, "Enter your current password"),
    newPassword: z.string().min(MIN_LENGTH, `Use at least ${MIN_LENGTH} characters`),
    confirm: z.string(),
  })
  .refine((v) => v.newPassword === v.confirm, { path: ["confirm"], message: "Passwords do not match" });

export function ChangePasswordForm({ onDone, submitLabel = "Change password" }: { onDone?: () => void; submitLabel?: string }) {
  const queryClient = useQueryClient();
  const form = useForm({ resolver: zodResolver(schema), defaultValues: { currentPassword: "", newPassword: "", confirm: "" } });
  const change = useMutation({
    mutationFn: (v: z.infer<typeof schema>) =>
      call(api.POST("/api/v1/auth/password", { body: { currentPassword: v.currentPassword, newPassword: v.newPassword } })),
    onSuccess: (me) => {
      queryClient.setQueryData(keys.me, me);
      onDone?.();
    },
  });
  const { errors } = form.formState;

  return (
    <form className="grid gap-4" onSubmit={form.handleSubmit((v) => change.mutate(v))}>
      <ErrorAlert error={change.error} />
      <Field label="Current password" error={errors.currentPassword?.message}>
        {(id) => <Input id={id} type="password" autoComplete="current-password" {...form.register("currentPassword")} />}
      </Field>
      <Field label="New password" hint={`At least ${MIN_LENGTH} characters.`} error={errors.newPassword?.message}>
        {(id) => <Input id={id} type="password" autoComplete="new-password" {...form.register("newPassword")} />}
      </Field>
      <Field label="Confirm new password" error={errors.confirm?.message}>
        {(id) => <Input id={id} type="password" autoComplete="new-password" {...form.register("confirm")} />}
      </Field>
      <Button type="submit" loading={change.isPending}>
        {submitLabel}
      </Button>
    </form>
  );
}
