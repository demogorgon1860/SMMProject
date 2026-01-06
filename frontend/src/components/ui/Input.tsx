import React, { forwardRef, useState } from 'react';
import { Eye, EyeOff, AlertCircle, CheckCircle } from 'lucide-react';

interface InputProps extends Omit<React.InputHTMLAttributes<HTMLInputElement>, 'size'> {
  label?: string;
  error?: string;
  success?: string;
  hint?: string;
  icon?: React.ReactNode;
  size?: 'sm' | 'md' | 'lg';
  fullWidth?: boolean;
}

const sizeStyles = {
  sm: 'px-3 py-1.5 text-sm rounded-lg',
  md: 'px-4 py-2.5 text-sm rounded-xl',
  lg: 'px-5 py-3 text-base rounded-xl',
};

export const Input = forwardRef<HTMLInputElement, InputProps>(
  (
    {
      label,
      error,
      success,
      hint,
      icon,
      size = 'md',
      fullWidth = true,
      type,
      className = '',
      disabled,
      ...props
    },
    ref
  ) => {
    const [showPassword, setShowPassword] = useState(false);
    const isPassword = type === 'password';
    const inputType = isPassword && showPassword ? 'text' : type;

    const hasError = !!error;
    const hasSuccess = !!success;

    return (
      <div className={fullWidth ? 'w-full' : ''}>
        {label && (
          <label className="block text-sm font-medium text-dark-700 dark:text-dark-200 mb-1.5">
            {label}
          </label>
        )}
        <div className="relative">
          {icon && (
            <div className="absolute left-3 top-1/2 -translate-y-1/2 text-dark-400 dark:text-dark-500">
              {icon}
            </div>
          )}
          <input
            ref={ref}
            type={inputType}
            disabled={disabled}
            className={`
              w-full
              bg-white dark:bg-dark-800
              border
              ${hasError
                ? 'border-red-500 dark:border-red-500 focus:ring-red-500/30 focus:border-red-500'
                : hasSuccess
                  ? 'border-accent-500 dark:border-accent-500 focus:ring-accent-500/30 focus:border-accent-500'
                  : 'border-dark-200 dark:border-dark-600 focus:ring-primary-500/30 focus:border-primary-500 dark:focus:border-primary-400'
              }
              text-dark-900 dark:text-white
              placeholder:text-dark-400 dark:placeholder:text-dark-500
              focus:outline-none focus:ring-2
              transition-all duration-200
              disabled:opacity-50 disabled:cursor-not-allowed disabled:bg-dark-50 dark:disabled:bg-dark-900
              ${sizeStyles[size]}
              ${icon ? 'pl-10' : ''}
              ${isPassword ? 'pr-10' : ''}
              ${className}
            `}
            {...props}
          />
          {isPassword && (
            <button
              type="button"
              onClick={() => setShowPassword(!showPassword)}
              className="absolute right-3 top-1/2 -translate-y-1/2 text-dark-400 hover:text-dark-600 dark:text-dark-500 dark:hover:text-dark-300 transition-colors"
              tabIndex={-1}
            >
              {showPassword ? <EyeOff size={18} /> : <Eye size={18} />}
            </button>
          )}
          {hasError && !isPassword && (
            <div className="absolute right-3 top-1/2 -translate-y-1/2 text-red-500">
              <AlertCircle size={18} />
            </div>
          )}
          {hasSuccess && !isPassword && (
            <div className="absolute right-3 top-1/2 -translate-y-1/2 text-accent-500">
              <CheckCircle size={18} />
            </div>
          )}
        </div>
        {(error || success || hint) && (
          <p
            className={`mt-1.5 text-sm ${
              hasError
                ? 'text-red-500'
                : hasSuccess
                  ? 'text-accent-600 dark:text-accent-400'
                  : 'text-dark-500 dark:text-dark-400'
            }`}
          >
            {error || success || hint}
          </p>
        )}
      </div>
    );
  }
);

Input.displayName = 'Input';

interface TextAreaProps extends React.TextareaHTMLAttributes<HTMLTextAreaElement> {
  label?: string;
  error?: string;
  hint?: string;
  fullWidth?: boolean;
}

export const TextArea = forwardRef<HTMLTextAreaElement, TextAreaProps>(
  ({ label, error, hint, fullWidth = true, className = '', disabled, ...props }, ref) => {
    const hasError = !!error;

    return (
      <div className={fullWidth ? 'w-full' : ''}>
        {label && (
          <label className="block text-sm font-medium text-dark-700 dark:text-dark-200 mb-1.5">
            {label}
          </label>
        )}
        <textarea
          ref={ref}
          disabled={disabled}
          className={`
            w-full
            bg-white dark:bg-dark-800
            border
            ${hasError
              ? 'border-red-500 dark:border-red-500 focus:ring-red-500/30 focus:border-red-500'
              : 'border-dark-200 dark:border-dark-600 focus:ring-primary-500/30 focus:border-primary-500 dark:focus:border-primary-400'
            }
            text-dark-900 dark:text-white
            placeholder:text-dark-400 dark:placeholder:text-dark-500
            px-4 py-2.5 text-sm rounded-xl
            focus:outline-none focus:ring-2
            transition-all duration-200
            disabled:opacity-50 disabled:cursor-not-allowed disabled:bg-dark-50 dark:disabled:bg-dark-900
            resize-none
            ${className}
          `}
          {...props}
        />
        {(error || hint) && (
          <p
            className={`mt-1.5 text-sm ${
              hasError ? 'text-red-500' : 'text-dark-500 dark:text-dark-400'
            }`}
          >
            {error || hint}
          </p>
        )}
      </div>
    );
  }
);

TextArea.displayName = 'TextArea';

interface SelectProps extends React.SelectHTMLAttributes<HTMLSelectElement> {
  label?: string;
  error?: string;
  hint?: string;
  options: { value: string; label: string }[];
  fullWidth?: boolean;
}

export const Select = forwardRef<HTMLSelectElement, SelectProps>(
  ({ label, error, hint, options, fullWidth = true, className = '', disabled, ...props }, ref) => {
    const hasError = !!error;

    return (
      <div className={fullWidth ? 'w-full' : ''}>
        {label && (
          <label className="block text-sm font-medium text-dark-700 dark:text-dark-200 mb-1.5">
            {label}
          </label>
        )}
        <select
          ref={ref}
          disabled={disabled}
          className={`
            w-full
            bg-white dark:bg-dark-800
            border
            ${hasError
              ? 'border-red-500 dark:border-red-500 focus:ring-red-500/30 focus:border-red-500'
              : 'border-dark-200 dark:border-dark-600 focus:ring-primary-500/30 focus:border-primary-500 dark:focus:border-primary-400'
            }
            text-dark-900 dark:text-white
            px-4 py-2.5 text-sm rounded-xl
            focus:outline-none focus:ring-2
            transition-all duration-200
            disabled:opacity-50 disabled:cursor-not-allowed disabled:bg-dark-50 dark:disabled:bg-dark-900
            cursor-pointer
            ${className}
          `}
          {...props}
        >
          {options.map(option => (
            <option key={option.value} value={option.value}>
              {option.label}
            </option>
          ))}
        </select>
        {(error || hint) && (
          <p
            className={`mt-1.5 text-sm ${
              hasError ? 'text-red-500' : 'text-dark-500 dark:text-dark-400'
            }`}
          >
            {error || hint}
          </p>
        )}
      </div>
    );
  }
);

Select.displayName = 'Select';
