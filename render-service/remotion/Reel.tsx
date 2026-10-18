import React, { useEffect, useState, useMemo } from 'react';
import {
  AbsoluteFill,
  Sequence,
  Audio,
  OffthreadVideo,
  interpolate,
  useCurrentFrame,
  useVideoConfig,
  delayRender,
  continueRender,
  cancelRender,
  staticFile,
  Easing
} from 'remotion';
import { ReelProps, SceneData, Word, sceneFrames, OUTRO_FRAMES } from './types';
import { brand } from '../src/brand';
import { isClaim, resolveCitation, stripSignOff, statFor, splitListItems } from '../src/textRules';
import { chunkWords, attachPunctuation, stripTrailingSignOff } from './subtitles';

// Single source of truth from brand.ts
const BRAND = brand;

// Monochrome fractal-noise tile for a faint paper texture (no colour literals).
const PAPER_GRAIN = `url("data:image/svg+xml;utf8,${encodeURIComponent(
  "<svg xmlns='http://www.w3.org/2000/svg' width='320' height='320'><filter id='n'><feTurbulence type='fractalNoise' baseFrequency='0.9' numOctaves='2' stitchTiles='stitch'/><feColorMatrix values='0 0 0 0 0  0 0 0 0 0  0 0 0 0 0  0 0 0 0.6 0'/></filter><rect width='100%' height='100%' filter='url(#n)'/></svg>"
)}")`;

// Helper to load fonts safely within Remotion
const FontLoader: React.FC<{ children: React.ReactNode }> = ({ children }) => {
  const [handle] = useState(() => delayRender('Loading Steer fonts'));

  useEffect(() => {
    const fonts = [
      new FontFace('Instrument Serif', `url(${staticFile('fonts/InstrumentSerif-Regular.woff2')})`, { weight: '400', style: 'normal' }),
      new FontFace('Instrument Serif', `url(${staticFile('fonts/InstrumentSerif-Italic.woff2')})`, { weight: '400', style: 'italic' }),
      new FontFace('Inter', `url(${staticFile('fonts/Inter-Regular.woff2')})`, { weight: '400', style: 'normal' }),
      new FontFace('Inter', `url(${staticFile('fonts/Inter-Medium.woff2')})`, { weight: '500', style: 'normal' }),
      new FontFace('Inter', `url(${staticFile('fonts/Inter-SemiBold.woff2')})`, { weight: '600', style: 'normal' }),
      new FontFace('Caveat', `url(${staticFile('fonts/Caveat-Medium.woff2')})`, { weight: '500', style: 'normal' }),
    ];

    Promise.all(fonts.map(f => f.load().then(loaded => document.fonts.add(loaded))))
      .then(() => {
        continueRender(handle);
      })
      .catch((err) => {
        // A fallback serif must never reach a rendered reel.
        cancelRender(new Error(`FONT_NOT_LOADED: ${err}`));
      });
  }, [handle]);

  return <>{children}</>;
};

// Display casing: keep the voiceover's own casing, but never shout.
const displayWord = (text: string) =>
  text.length >= 4 && text === text.toUpperCase() && /[A-Z]/.test(text) ? text.toLowerCase() : text;

// Subtitles: phrase-aware chunks of at most 5 words (see subtitles.ts),
// displays the active chunk and highlights the spoken word.
const Subtitles: React.FC<{
  words: Word[];
  voiceover?: string | null;
  sceneTimeMs: number;
  isMaroon: boolean;
  isClosing: boolean;
}> = ({ words, voiceover, sceneTimeMs, isMaroon, isClosing }) => {
  const chunks = useMemo(() => {
    if (!words || words.length === 0) return [];
    let ws = attachPunctuation(words, voiceover);
    if (isClosing) ws = stripTrailingSignOff(ws);
    return chunkWords(ws);
  }, [words, voiceover, isClosing]);

  if (chunks.length === 0) return null;

  // Find active chunk; once the last chunk has been spoken, hide the pill.
  const lastEnd = chunks[chunks.length - 1][chunks[chunks.length - 1].length - 1].endMs;
  if (sceneTimeMs > lastEnd + 600) return null;
  let activeChunk = chunks[0];
  for (const c of chunks) {
    const start = c[0].startMs;
    const end = c[c.length - 1].endMs;
    if (sceneTimeMs >= start - 100 && sceneTimeMs <= end + 150) {
      activeChunk = c;
      break;
    }
    if (sceneTimeMs > end) {
      activeChunk = c;
    }
  }

  // Keep a chunk on one line: shrink long chunks instead of wrapping.
  const chunkChars = activeChunk.reduce((n, w) => n + w.text.length + 1, 0);
  const fontSize = Math.max(34, Math.min(46, Math.floor(820 / (chunkChars * 0.56))));

  const pillBg = isMaroon ? BRAND.maroon : BRAND.softPaper;
  const defaultTextColor = isMaroon ? BRAND.cream : BRAND.ink;
  const activeWordColor = isMaroon ? BRAND.blush : BRAND.rust;

  return (
    <div
      style={{
        position: 'absolute',
        bottom: 300,
        left: 0,
        right: 0,
        display: 'flex',
        justifyContent: 'center',
        padding: '0 80px',
        zIndex: 10,
      }}
    >
      <div
        style={{
          backgroundColor: pillBg,
          border: `1px solid ${isMaroon ? 'rgba(230, 203, 189, 0.25)' : BRAND.blush}`,
          borderRadius: 999,
          padding: '18px 28px',
          boxShadow: '0 8px 30px rgba(0,0,0,0.06)',
          display: 'flex',
          gap: 12,
          flexWrap: 'wrap',
          justifyContent: 'center',
          maxWidth: 920,
        }}
      >
        {activeChunk.map((w, idx) => {
          const isSpoken = sceneTimeMs >= w.startMs && sceneTimeMs <= w.endMs;
          const displayText = displayWord(w.text);

          return (
            <span
              key={idx}
              style={{
                fontFamily: "'Inter', sans-serif",
                fontSize,
                fontWeight: 600,
                whiteSpace: 'nowrap',
                color: isSpoken ? activeWordColor : defaultTextColor,
                transition: 'color 0.1s ease',
              }}
            >
              {displayText}
            </span>
          );
        })}
      </div>
    </div>
  );
};

// Individual Scene View
const SceneView: React.FC<{
  scene: SceneData;
  durationInFrames: number;
  citation: string;
}> = ({ scene, durationInFrames, citation }) => {
  const frame = useCurrentFrame();
  const { fps } = useVideoConfig();
  const sceneTimeMs = (frame / fps) * 1000;

  const isClosing = scene.motion === 'closing';
  const isMaroon = isClosing;
  const hasBroll = Boolean(scene.brollSrc) && !isClosing;
  const claimScene = isClaim(scene);
  const onScreen = stripSignOff(scene.onScreen || '');
  const sceneCitation = claimScene ? resolveCitation(scene, citation) : '';

  // Motion fallbacks: quote only for claims, stat only for statEligible
  // outcome values, list only with two or more items. Otherwise title.
  const stat = statFor(scene, onScreen);
  const listItems = splitListItems(onScreen);
  let motion = scene.motion || 'title';
  if (motion === 'quote' && !claimScene) motion = 'title';
  if (motion === 'stat' && !stat) motion = 'title';
  if (motion === 'list' && listItems.length < 2) motion = 'title';

  // Very slow, faint drift of two large soft shapes when there is no b-roll.
  const drift1X = interpolate(frame, [0, durationInFrames], [-120, -100]);
  const drift1Y = interpolate(frame, [0, durationInFrames], [80, 96]);
  const drift2X = interpolate(frame, [0, durationInFrames], [380, 364]);
  const drift2Y = interpolate(frame, [0, durationInFrames], [1080, 1064]);

  // Ken Burns zoom on b-roll
  const brollScale = interpolate(frame, [0, durationInFrames], [1.0, 1.06], {
    extrapolateRight: 'clamp',
  });

  // Motion text animation: fades + rises 40px over 12 frames
  const textOpacity = interpolate(frame, [0, 12], [0, 1], { extrapolateRight: 'clamp' });
  const textTranslateY = interpolate(frame, [0, 12], [40, 0], {
    easing: Easing.out(Easing.quad),
    extrapolateRight: 'clamp',
  });

  // Italic phrase delay: fades in 6 frames later (frame 6 to 18)
  const italicOpacity = interpolate(frame, [6, 18], [0, 1], { extrapolateRight: 'clamp' });

  // Format onScreen mixed italic text
  const renderFormattedHeadline = (text: string, baseColor: string, accentColor: string, size: number = 88) => {
    const parts = text.split(/(\*[^*]+\*)/g);
    return (
      <div
        style={{
          fontFamily: "'Instrument Serif', serif",
          fontSize: size,
          lineHeight: 1.05,
          color: baseColor,
          textAlign: 'center',
          maxWidth: 888,
          margin: '0 auto',
        }}
      >
        {parts.map((p, i) => {
          if (p.startsWith('*') && p.endsWith('*')) {
            return (
              <span
                key={i}
                style={{
                  fontStyle: 'italic',
                  color: accentColor,
                  opacity: italicOpacity,
                  display: 'inline-block',
                }}
              >
                {p.slice(1, -1)}
              </span>
            );
          }
          return <span key={i}>{p}</span>;
        })}
      </div>
    );
  };

  // Render specific motion content
  const renderMotionContent = () => {
    const baseColor = isMaroon ? BRAND.cream : BRAND.ink;
    const accentColor = isMaroon ? BRAND.blush : BRAND.rust;

    if (motion === 'closing') {
      const closingOpacity = interpolate(frame, [0, 20], [0, 1], { extrapolateRight: 'clamp' });
      return (
        <div style={{ opacity: closingOpacity, textAlign: 'center' }}>
          {onScreen && renderFormattedHeadline(onScreen, BRAND.cream, BRAND.blush, 88)}
          <div
            style={{
              fontFamily: "'Instrument Serif', serif",
              fontSize: 96,
              color: BRAND.cream,
              marginTop: 48,
              lineHeight: 1,
            }}
          >
            steer
          </div>
          <div
            style={{
              fontFamily: "'Inter', sans-serif",
              fontSize: 32,
              fontWeight: 500,
              color: BRAND.blush,
              marginTop: 16,
            }}
          >
            steerclub.com
          </div>
        </div>
      );
    }

    if (motion === 'stat' && stat) {
      const numMatch = stat.match(/[-−]?\d+(?:[.,]\d+)?/);
      const rawNum = numMatch ? parseFloat(numMatch[0].replace(',', '.').replace('−', '-')) : NaN;
      const unit = numMatch ? stat.slice(stat.indexOf(numMatch[0]) + numMatch[0].length).trim() : '';
      // Count up from 0 to the value over 20 frames
      const currentCount = interpolate(frame, [0, 20], [0, isNaN(rawNum) ? 0 : rawNum], {
        extrapolateRight: 'clamp',
      });
      const displayCount = isNaN(rawNum)
        ? stat
        : `${rawNum % 1 !== 0 ? currentCount.toFixed(1) : Math.round(currentCount)}${unit === '%' ? '%' : unit ? ' ' + unit : ''}`;
      const restOfText = onScreen.replace(stat, '').replace(/\*/g, '').replace(/\s{2,}/g, ' ').trim();

      return (
        <div style={{ textAlign: 'center' }}>
          <div
            style={{
              fontFamily: "'Instrument Serif', serif",
              fontSize: 220,
              lineHeight: 1,
              color: BRAND.rust,
              marginBottom: 20,
            }}
          >
            {displayCount}
          </div>
          {restOfText && (
            <div
              style={{
                fontFamily: "'Inter', sans-serif",
                fontSize: 56,
                lineHeight: 1.25,
                fontWeight: 500,
                color: BRAND.ink,
                maxWidth: 888,
                margin: '0 auto',
              }}
            >
              {restOfText}
            </div>
          )}
        </div>
      );
    }

    if (motion === 'list') {
      const items = listItems;
      return (
        <div style={{ maxWidth: 888, margin: '0 auto', textAlign: 'left' }}>
          {items.slice(0, 3).map((item, idx) => {
            const itemDelay = idx * 8;
            const itemOpacity = interpolate(frame, [itemDelay, itemDelay + 10], [0, 1], {
              extrapolateRight: 'clamp',
            });
            const itemY = interpolate(frame, [itemDelay, itemDelay + 10], [20, 0], {
              extrapolateRight: 'clamp',
            });
            return (
              <div
                key={idx}
                style={{
                  opacity: itemOpacity,
                  transform: `translateY(${itemY}px)`,
                  display: 'flex',
                  alignItems: 'center',
                  gap: 20,
                  marginBottom: 24,
                }}
              >
                <div
                  style={{
                    width: 14,
                    height: 14,
                    borderRadius: '50%',
                    backgroundColor: BRAND.rust,
                    flexShrink: 0,
                  }}
                />
                <div
                  style={{
                    fontFamily: "'Inter', sans-serif",
                    fontSize: 52,
                    fontWeight: 500,
                    color: BRAND.ink,
                    lineHeight: 1.25,
                  }}
                >
                  {item.replace(/\*/g, '')}
                </div>
              </div>
            );
          })}
        </div>
      );
    }

    if (motion === 'quote') {
      return (
        <div
          style={{
            backgroundColor: BRAND.softPaper,
            border: `1px solid ${BRAND.blush}`,
            borderRadius: 32,
            padding: '56px 48px',
            maxWidth: 888,
            margin: '0 auto',
          }}
        >
          <div
            style={{
              fontFamily: "'Instrument Serif', serif",
              fontSize: 64,
              fontStyle: 'italic',
              lineHeight: 1.15,
              color: BRAND.ink,
              marginBottom: 24,
            }}
          >
            {onScreen.replace(/\*/g, '')}
          </div>
          {sceneCitation && (
            <div
              style={{
                fontFamily: "'Inter', sans-serif",
                fontSize: 26,
                fontWeight: 500,
                color: BRAND.clay,
              }}
            >
              {sceneCitation}
            </div>
          )}
        </div>
      );
    }

    // Default: 'title' motion
    return renderFormattedHeadline(onScreen, baseColor, accentColor, 88);
  };

  return (
    <AbsoluteFill
      style={{
        backgroundColor: isMaroon ? BRAND.maroon : BRAND.cream,
        overflow: 'hidden',
      }}
    >
      {/* Layer 1: Background */}
      {hasBroll ? (
        <>
          <OffthreadVideo
            src={scene.brollSrc!}
            muted
            style={{
              position: 'absolute',
              top: 0,
              left: 0,
              width: 1080,
              height: 1920,
              objectFit: 'cover',
              transform: `scale(${brollScale})`,
              filter: 'blur(2px)',
            }}
          />
          <div
            style={{
              position: 'absolute',
              top: 0,
              left: 0,
              width: 1080,
              height: 1920,
              backgroundColor: BRAND.cream,
              opacity: 0.55,
            }}
          />
        </>
      ) : !isMaroon ? (
        <>
          {/* Faint, large, very soft blush shapes with a slow drift */}
          <div
            style={{
              position: 'absolute',
              left: `${drift1X}px`,
              top: `${drift1Y}px`,
              width: 900,
              height: 900,
              borderRadius: '50%',
              backgroundColor: BRAND.blush,
              opacity: 0.16,
              filter: 'blur(160px)',
              pointerEvents: 'none',
            }}
          />
          <div
            style={{
              position: 'absolute',
              left: `${drift2X}px`,
              top: `${drift2Y}px`,
              width: 1000,
              height: 1000,
              borderRadius: '50%',
              backgroundColor: BRAND.blush,
              opacity: 0.12,
              filter: 'blur(180px)',
              pointerEvents: 'none',
            }}
          />
          {/* Paper grain */}
          <div
            style={{
              position: 'absolute',
              inset: 0,
              backgroundImage: PAPER_GRAIN,
              backgroundSize: '320px 320px',
              opacity: 0.05,
              pointerEvents: 'none',
            }}
          />
        </>
      ) : null}

      {/* Layer 2: Motion Text */}
      <div
        style={{
          position: 'absolute',
          top: 0,
          left: 96,
          right: 96,
          bottom: 0,
          display: 'flex',
          flexDirection: 'column',
          justifyContent: 'center',
          alignItems: 'center',
          opacity: textOpacity,
          transform: `translateY(${textTranslateY}px)`,
          zIndex: 8,
        }}
      >
        {renderMotionContent()}
      </div>

      {/* Layer 3: Subtitles */}
      <Subtitles
        words={scene.words}
        voiceover={scene.voiceover}
        sceneTimeMs={sceneTimeMs}
        isMaroon={isMaroon}
        isClosing={isClosing}
      />

      {/* Layer 4: Persistent overlays per scene */}
      {scene.kind === 'illustration' && (
        <div
          style={{
            position: 'absolute',
            top: 220,
            left: 0,
            right: 0,
            textAlign: 'center',
            fontFamily: "'Caveat', cursive",
            fontSize: 30,
            color: BRAND.clay,
            zIndex: 10,
          }}
        >
          an everyday example
        </div>
      )}

      {sceneCitation && motion !== 'quote' && (
        <div
          style={{
            position: 'absolute',
            bottom: 220,
            left: 96,
            right: 96,
            textAlign: 'center',
            fontFamily: "'Inter', sans-serif",
            fontSize: 22,
            color: BRAND.clay,
            zIndex: 10,
          }}
        >
          {sceneCitation}
        </div>
      )}

      {/* Layer 5: Scene Voiceover Audio */}
      {scene.audioSrc && <Audio src={scene.audioSrc} />}
    </AbsoluteFill>
  );
};

export const Reel: React.FC<ReelProps> = ({ scenes, citation, draft, musicSrc }) => {
  const frame = useCurrentFrame();
  const { fps, durationInFrames } = useVideoConfig();

  // Calculate sequence ranges; the last scene also holds the 1s outro.
  let accumulatedFrames = 0;
  const sequences = scenes.map((s, i) => {
    const durationFrames = sceneFrames(s, fps) + (i === scenes.length - 1 ? OUTRO_FRAMES : 0);
    const startFrame = accumulatedFrames;
    accumulatedFrames += durationFrames;
    return {
      scene: s,
      startFrame,
      durationFrames,
    };
  });

  // The closing scene draws its own wordmark + steerclub.com, so the
  // persistent top wordmark is hidden there (one sign-off only).
  const closingSeq = sequences.find(q => q.scene.motion === 'closing');
  const inClosing = closingSeq ? frame >= closingSeq.startFrame : false;

  // Music volume with fade-in (15 frames) and fade-out (30 frames)
  const musicVolume = interpolate(
    frame,
    [0, 15, durationInFrames - 30, durationInFrames],
    [0, 0.08, 0.08, 0],
    { extrapolateLeft: 'clamp', extrapolateRight: 'clamp' }
  );

  return (
    <FontLoader>
      <AbsoluteFill style={{ backgroundColor: BRAND.cream }}>
        {/* Scene Sequences */}
        {sequences.map(({ scene, startFrame, durationFrames }, i) => (
          <Sequence key={i} from={startFrame} durationInFrames={durationFrames}>
            <SceneView
              scene={scene}
              durationInFrames={durationFrames}
              citation={citation}
            />
          </Sequence>
        ))}

        {/* Global Persistent Wordmark & Watermark */}
        <div
          style={{
            position: 'absolute',
            top: 80,
            left: 96,
            right: 96,
            display: 'flex',
            justifyContent: 'space-between',
            alignItems: 'center',
            zIndex: 30,
          }}
        >
          <div
            style={{
              fontFamily: "'Instrument Serif', serif",
              fontSize: 44,
              color: BRAND.rust,
              lineHeight: 1,
              opacity: inClosing ? 0 : 1,
            }}
          >
            steer
          </div>

          {draft && (
            <div
              style={{
                fontFamily: "'Inter', sans-serif",
                fontSize: 24,
                fontWeight: 500,
                color: BRAND.clay,
                backgroundColor: 'rgba(248, 242, 235, 0.85)',
                border: `1px solid ${BRAND.blush}`,
                borderRadius: 999,
                padding: '6px 18px',
              }}
            >
              draft · pending doctor review
            </div>
          )}
        </div>

        {/* Global Music Audio */}
        {musicSrc && <Audio src={musicSrc} volume={musicVolume} />}
      </AbsoluteFill>
    </FontLoader>
  );
};
