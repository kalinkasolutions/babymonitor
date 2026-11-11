import { Availability } from '../protocol/signal';

export type Reachability = 'ready' | 'not-armed' | 'in-a-call' | 'unreachable';

export function reachabilityOf(
  online: boolean,
  availability: Availability | undefined,
): Reachability {
  if (!online) {
    return 'unreachable';
  }
  if (availability?.armed === false) {
    return 'not-armed';
  }
  if (availability?.busy === true) {
    return 'in-a-call';
  }
  return 'ready';
}

/** The same lines the app shows in its device list. */
export function reachabilityLine(reachability: Reachability, name: string): string {
  switch (reachability) {
    case 'ready':
      return 'Ready';
    case 'not-armed':
      return `Not set up as a monitor. On ${name}, turn on “Use this phone as the monitor”.`;
    case 'in-a-call':
      return 'In a call with another phone';
    case 'unreachable':
      return 'Not reachable. Open the app on it.';
  }
}
