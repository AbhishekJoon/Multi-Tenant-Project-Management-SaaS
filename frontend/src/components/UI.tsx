import type { ReactNode } from "react";
import * as Dialog from "@radix-ui/react-dialog";
import { X, Inbox, AlertCircle } from "lucide-react";
export function Loading() {
  return (
    <div className="loading" role="status">
      <div className="skeleton" />
      <div className="skeleton" />
      <div className="skeleton" />
      <span>Loading your workspace…</span>
    </div>
  );
}
export function ErrorState({ error }: { error: unknown }) {
  return (
    <div className="error-banner" role="alert">
      <AlertCircle size={18} />{" "}
      {error instanceof Error ? error.message : "Unable to load this page"}
    </div>
  );
}
export function Empty({
  title,
  children,
}: {
  title: string;
  children?: ReactNode;
}) {
  return (
    <div className="empty">
      <Inbox size={32} />
      <h3>{title}</h3>
      {children}
    </div>
  );
}
export function Modal({
  title,
  open,
  onOpenChange,
  children,
}: {
  title: string;
  open: boolean;
  onOpenChange: (open: boolean) => void;
  children: ReactNode;
}) {
  return (
    <Dialog.Root open={open} onOpenChange={onOpenChange}>
      <Dialog.Portal>
        <Dialog.Overlay className="overlay" />
        <Dialog.Content className="modal" aria-describedby={undefined}>
          <header>
            <Dialog.Title>{title}</Dialog.Title>
            <Dialog.Close className="icon-button" aria-label="Close dialog">
              <X size={20} />
            </Dialog.Close>
          </header>
          {children}
        </Dialog.Content>
      </Dialog.Portal>
    </Dialog.Root>
  );
}
