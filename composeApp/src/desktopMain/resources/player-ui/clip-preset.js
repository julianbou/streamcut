// A clip range the player was opened with.
//
// The app can open the player on a range worked out elsewhere -- an assistant
// proposing a cut over MCP -- so the In and Out points are already where it
// suggests and the user only has to check and export. Kotlin sends the range in
// the control state; this applies it once, as if it had been typed.
//
// Loaded after clip-controls.js, so `state`, `clipDraftDurationMs` and
// window.clipUi are already bound. In its own file for the same reason the rest
// of the clip UI is: upstream Nuvio merges never touch it.

/** The token of the range already applied, so a state push does not re-apply it over the user's edits. */
let clipPresetAppliedToken = 0;

const clipPresetApply = () => {
  const token = Number(state.clipPresetToken) || 0;
  if (!token || token === clipPresetAppliedToken) return;
  // setRange clamps to the duration, so it has to be known first. Until the
  // source reports one this is retried on every render and playback tick.
  if (clipDraftDurationMs <= 0) return;
  clipPresetAppliedToken = token;
  window.clipUi.setRange(Number(state.clipPresetInMs) || 0, Number(state.clipPresetOutMs) || 0);
};

{
  const render = window.clipUi.render;
  const syncPlayback = window.clipUi.syncPlayback;
  window.clipUi.render = (...args) => {
    render(...args);
    clipPresetApply();
  };
  window.clipUi.syncPlayback = (...args) => {
    syncPlayback(...args);
    clipPresetApply();
  };
}
