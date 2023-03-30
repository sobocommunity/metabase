import React, {
  ReactNode,
  useCallback,
  useEffect,
  useRef,
  useState,
} from "react";

import Toaster, { DEFAULT_TOASTER_DURATION } from ".";

interface ToasterApi {
  show: ShowToaster;
  hide: HideToaster;
}

interface ShowToasterProps {
  message: string;
  confirmText: string;
  onConfirm: () => void;
  duration?: number;
}
type ShowToaster = (props: ShowToasterProps) => void;
type HideToaster = () => void;

export function useToaster(): [ToasterApi, ReactNode] {
  // const [duration, setDuration] = useState<number>(0);
  const timer = useRef<number>();
  const [isShown, setIsShown] = useState<boolean>(false);
  const [options, setOptions] = useState<Omit<ShowToasterProps, "duration">>();

  const hide: HideToaster = useCallback(() => {
    setIsShown(false);
    clearTimeout(timer.current);
  }, []);

  const show: ShowToaster = useCallback(
    ({ duration = DEFAULT_TOASTER_DURATION, ...options }) => {
      // XXX: Using just `setTimeout` will make TypeScript complains. See https://stackoverflow.com/a/55550147
      timer.current = window.setTimeout(hide, duration);
      setIsShown(true);
      setOptions(options);
    },
    [hide],
  );

  useEffect(() => {
    return () => {
      clearTimeout(timer.current);
    };
  });

  const api = {
    show,
    hide,
  };

  const toaster = options ? (
    <Toaster isShown={isShown} fixed onDismiss={hide} {...options} />
  ) : null;

  return [api, toaster];
}
