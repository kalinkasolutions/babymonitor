import {
  IceCandidateDto,
  LightMode,
  LightRequest,
  SignalKinds,
  StopReasons,
} from '../protocol/signal';

export type CaptureQuality = 'Low' | 'Standard' | 'High';

export const CaptureQualities: { value: CaptureQuality; label: string; detail: string }[] = [
  { value: 'Low', label: 'Low', detail: '320x240, 10fps' },
  { value: 'Standard', label: 'Standard', detail: '640x480, 15fps' },
  { value: 'High', label: 'High', detail: '1280x720, 24fps' },
];

/** Long enough to see the room, short enough not to be a wake-up call. */
export const GlanceSeconds = 8;

export interface LightState {
  mode: LightMode;
  brightness: number;
}

/** The browser only ever listens, so unlike the app there is no sending side here. */
export type CallState =
  | { kind: 'idle' }
  | { kind: 'asking'; peer: string }
  | { kind: 'negotiating'; peer: string }
  | { kind: 'live'; peer: string }
  | { kind: 'failed'; reason: string };

export type CallEvent =
  | { kind: 'listen'; peer: string; video: boolean }
  | { kind: 'choose-quality'; quality: CaptureQuality }
  | { kind: 'ask-for-video'; on: boolean }
  | { kind: 'ask-for-light'; mode: LightMode; brightness: number; seconds: number }
  | { kind: 'offer-received'; peer: string; sdp: string }
  | { kind: 'candidate-received'; peer: string; candidate: IceCandidateDto }
  | { kind: 'local-answer'; sdp: string }
  | { kind: 'local-candidate'; candidate: IceCandidateDto }
  | { kind: 'connected' }
  | { kind: 'stopped'; peer: string }
  | { kind: 'hang-up' }
  | { kind: 'failure'; reason: string };

export type CallEffect =
  | { kind: 'open' }
  | { kind: 'accept-offer'; sdp: string }
  | { kind: 'add-candidate'; candidate: IceCandidateDto }
  | { kind: 'send'; to: string; signal: string; body: string }
  | { kind: 'close' };

export interface CallSession {
  state: CallState;
  quality: CaptureQuality;
  light: LightState;
  video: boolean;
  /** Candidates that came before the offer they belong to; WebRTC would reject them. */
  pendingCandidates: IceCandidateDto[];
  remoteDescriptionSet: boolean;
}

export const IdleSession: CallSession = {
  state: { kind: 'idle' },
  quality: 'Standard',
  light: { mode: 'Off', brightness: 1 },
  video: false,
  pendingCandidates: [],
  remoteDescriptionSet: false,
};

export function peerOf(state: CallState): string | null {
  return 'peer' in state ? state.peer : null;
}

/** Ported from the app's CallSession.kt, listening side: one event in, the next session and what to do. */
export function onCallEvent(session: CallSession, event: CallEvent): [CallSession, CallEffect[]] {
  const peer = peerOf(session.state);
  const isFrom = (deviceId: string) => peer === deviceId;
  const send = (signal: string, body: string): CallEffect[] =>
    peer ? [{ kind: 'send', to: peer, signal, body }] : [];

  switch (event.kind) {
    case 'listen':
      if (peer) {
        return [session, []];
      }
      return [
        { ...session, state: { kind: 'asking', peer: event.peer }, video: event.video },
        [
          {
            kind: 'send',
            to: event.peer,
            signal: SignalKinds.Start,
            body: JSON.stringify({ video: event.video, quality: session.quality }),
          },
        ],
      ];

    case 'offer-received':
      if (!isFrom(event.peer)) {
        return [
          session,
          [{ kind: 'send', to: event.peer, signal: SignalKinds.Stop, body: StopReasons.Busy }],
        ];
      }
      return [
        { ...session, state: { kind: 'negotiating', peer: event.peer } },
        [{ kind: 'open' }, { kind: 'accept-offer', sdp: event.sdp }],
      ];

    case 'candidate-received':
      if (!isFrom(event.peer)) {
        return [session, []];
      }
      if (!session.remoteDescriptionSet) {
        return [
          { ...session, pendingCandidates: [...session.pendingCandidates, event.candidate] },
          [],
        ];
      }
      return [session, [{ kind: 'add-candidate', candidate: event.candidate }]];

    // An answer exists only once the offer was applied, so whatever was queued can go now.
    case 'local-answer':
      return [
        { ...session, remoteDescriptionSet: true, pendingCandidates: [] },
        [
          ...send(SignalKinds.Answer, event.sdp),
          ...session.pendingCandidates.map((candidate): CallEffect => ({
            kind: 'add-candidate',
            candidate,
          })),
        ],
      ];

    case 'local-candidate':
      return [session, send(SignalKinds.Candidate, JSON.stringify(event.candidate))];

    case 'choose-quality': {
      const next = { ...session, quality: event.quality };
      return [next, send(SignalKinds.Quality, JSON.stringify({ quality: event.quality }))];
    }

    case 'ask-for-video': {
      const next = { ...session, video: event.on };
      return [next, send(SignalKinds.Video, JSON.stringify({ on: event.on }))];
    }

    // Only while there is a call: a light nobody is watching is just a light left on.
    case 'ask-for-light': {
      if (session.state.kind !== 'live') {
        return [session, []];
      }
      const request: LightRequest = {
        mode: event.mode,
        brightness: event.brightness,
        seconds: event.seconds,
        reason: '',
      };
      return [
        { ...session, light: { mode: event.mode, brightness: event.brightness } },
        send(SignalKinds.Light, JSON.stringify(request)),
      ];
    }

    case 'connected':
      if (session.state.kind !== 'negotiating') {
        return [session, []];
      }
      return [{ ...session, state: { kind: 'live', peer: session.state.peer } }, []];

    case 'stopped':
      if (!isFrom(event.peer)) {
        return [session, []];
      }
      return [IdleSession, [{ kind: 'close' }]];

    // The light goes out with the call: a nursery left lit because a tab closed is not forgivable.
    case 'hang-up': {
      if (!peer) {
        return [session, []];
      }
      const lightOff: LightRequest = { mode: 'Off', brightness: 1, seconds: 0, reason: '' };
      return [
        IdleSession,
        [
          ...send(SignalKinds.Light, JSON.stringify(lightOff)),
          ...send(SignalKinds.Stop, ''),
          { kind: 'close' },
        ],
      ];
    }

    case 'failure':
      return [
        { ...IdleSession, state: { kind: 'failed', reason: event.reason } },
        [...send(SignalKinds.Stop, event.reason), { kind: 'close' }],
      ];
  }
}
