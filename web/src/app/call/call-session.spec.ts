import { describe, expect, it } from 'vitest';
import { CallEvent, CallSession, IdleSession, onCallEvent } from './call-session';

const room = 'r'.repeat(32);
const stranger = 's'.repeat(32);
const candidate = {
  sdpMid: '0',
  sdpMLineIndex: 0,
  candidate: 'candidate:1 1 udp 1 10.0.0.2 5000 typ host',
};

function run(events: CallEvent[], from: CallSession = IdleSession) {
  let session = from;
  const effects = [];
  for (const event of events) {
    const [next, produced] = onCallEvent(session, event);
    session = next;
    effects.push(...produced);
  }
  return { session, effects };
}

describe('call session', () => {
  it('asks the room to start, saying whether to bring the camera', () => {
    const { session, effects } = run([{ kind: 'listen', peer: room, video: true }]);

    expect(session.state).toEqual({ kind: 'asking', peer: room });
    expect(effects).toEqual([
      { kind: 'send', to: room, signal: 'start', body: '{"video":true,"quality":"Standard"}' },
    ]);
  });

  it('answers the offer from the phone it asked', () => {
    const { session, effects } = run([
      { kind: 'listen', peer: room, video: false },
      { kind: 'offer-received', peer: room, sdp: 'v=0' },
    ]);

    expect(session.state).toEqual({ kind: 'negotiating', peer: room });
    expect(effects.slice(1)).toEqual([{ kind: 'open' }, { kind: 'accept-offer', sdp: 'v=0' }]);
  });

  it('tells a phone it did not ask that it is busy', () => {
    const { effects } = run([
      { kind: 'listen', peer: room, video: false },
      { kind: 'offer-received', peer: stranger, sdp: 'v=0' },
    ]);

    expect(effects[1]).toEqual({
      kind: 'send',
      to: stranger,
      signal: 'stop',
      body: 'That phone is already in a call.',
    });
  });

  it('holds candidates that arrive before the offer is applied, then adds them', () => {
    const { effects } = run([
      { kind: 'listen', peer: room, video: false },
      { kind: 'offer-received', peer: room, sdp: 'v=0' },
      { kind: 'candidate-received', peer: room, candidate },
      { kind: 'local-answer', sdp: 'answer' },
      { kind: 'candidate-received', peer: room, candidate },
    ]);

    expect(effects.slice(3)).toEqual([
      { kind: 'send', to: room, signal: 'answer', body: 'answer' },
      { kind: 'add-candidate', candidate },
      { kind: 'add-candidate', candidate },
    ]);
  });

  it('turns the light off before stopping when hanging up', () => {
    const { session, effects } = run([
      { kind: 'listen', peer: room, video: false },
      { kind: 'offer-received', peer: room, sdp: 'v=0' },
      { kind: 'connected' },
      { kind: 'hang-up' },
    ]);

    expect(session).toEqual(IdleSession);
    expect(
      effects.slice(-3).map((effect) => ('signal' in effect ? effect.signal : effect.kind)),
    ).toEqual(['light', 'stop', 'close']);
  });

  it('only sends the light while the call is live', () => {
    const asking = run([{ kind: 'listen', peer: room, video: false }]).session;

    expect(
      onCallEvent(asking, { kind: 'ask-for-light', mode: 'Red', brightness: 0.3, seconds: 0 })[1],
    ).toEqual([]);
  });

  it('ignores a stop from a phone it is not talking to', () => {
    const asking = run([{ kind: 'listen', peer: room, video: false }]).session;

    expect(onCallEvent(asking, { kind: 'stopped', peer: stranger })).toEqual([asking, []]);
  });
});
