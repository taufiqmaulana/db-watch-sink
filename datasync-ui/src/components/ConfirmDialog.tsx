import type { ReactNode } from "react";
import { Button } from "./ui/button";
import { Dialog } from "./ui/dialog";

/** Confirms a destructive or expensive action; {@code effect} states what will happen. */
export function ConfirmDialog({
  open,
  onOpenChange,
  title,
  effect,
  confirmLabel,
  destructive,
  loading,
  onConfirm,
}: {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  title: string;
  effect: ReactNode;
  confirmLabel: string;
  destructive?: boolean;
  loading?: boolean;
  onConfirm: () => void;
}) {
  return (
    <Dialog
      open={open}
      onOpenChange={onOpenChange}
      title={title}
      description={effect}
      footer={
        <>
          <Button variant="outline" onClick={() => onOpenChange(false)}>
            Cancel
          </Button>
          <Button variant={destructive ? "destructive" : "primary"} loading={loading} onClick={onConfirm}>
            {confirmLabel}
          </Button>
        </>
      }
    />
  );
}
