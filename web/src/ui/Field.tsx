import { cloneElement, ReactElement, ReactNode, useId } from 'react';

interface FieldProps {
  label: ReactNode;
  hint?: ReactNode;
  error?: ReactNode;
  /** one input, select or textarea; it gets the id, `aria-describedby` and `aria-invalid` */
  children: ReactElement;
}

export function Field({ label, hint, error, children }: FieldProps) {
  const auto = useId();
  const id: string = children.props.id ?? auto;
  const hintId = hint ? `${id}-hint` : undefined;
  const errorId = error ? `${id}-error` : undefined;
  const describedBy = [children.props['aria-describedby'], hintId, errorId].filter(Boolean).join(' ') || undefined;
  return (
    <div className={error ? 'field field-invalid' : 'field'}>
      <label className="field-label" htmlFor={id}>{label}</label>
      {cloneElement(children, { id, 'aria-describedby': describedBy, 'aria-invalid': error ? true : undefined })}
      {hint && <div className="field-hint" id={hintId}>{hint}</div>}
      {error && <div className="field-error" id={errorId} role="alert">{error}</div>}
    </div>
  );
}
