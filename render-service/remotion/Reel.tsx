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
  Easing
} from 'remotion';
import { ReelProps, SceneData, Word } from './types';
import { brand } from '../src/brand';

// Single source of truth from brand.ts
const BRAND = brand;

// Helper to load fonts safely within Remotion
const FontLoader: React.FC<{ children: React.ReactNode }> = ({ children }) => {
  const [handle] = useState(() => delayRender('Loading Steer fonts'));

  useEffect(() => {
    const fonts = [
      new FontFace('Instrument Serif', 'url(/assets/fonts/InstrumentSerif-Regular.woff2)', { weight: '400', style: 'normal' }),
      new FontFace('Instrument Serif', 'url(/assets/fonts/InstrumentSerif-Italic.woff2)', { weight: '400', style: 'italic' }),
      new FontFace('Inter', 'url(/assets/fonts/Inter-Regular.woff2)', { weight: '400', style: 'normal' }),
      new FontFace('Inter', 'url(/assets/fonts/Inter-Medium.woff2)', { weight: '500', style: 'normal' }),
      new FontFace('Inter', 'url(/assets/fonts/Inter-SemiBold.woff2)', { weight: '600', style: 'normal' }),
      new FontFace('Caveat', 'url(/assets/fonts/Caveat-Medium.woff2)', { weight: '500', style: 'normal' }),
    ];

    Promise.all(fonts.map(f => f.load().then(loaded => document.fonts.add(loaded))))
      .then(() => {
        continueRender(handle);
      })
      .catch((err) => {
        console.warn('Font load warning in Remotion:', err);
        continueRender(handle);
      });
  }, [handle]);

  return <>{children}</>;
};

// Subtitles Component: Chunks words into 3-5 words, displays active chunk, highlights active word in rust
const Subtitles: React.FC<{
  words: Word[];
  sceneTimeMs: number;
  isMaroon: boolean;
}> = ({ words, sceneTimeMs, isMaroon }) => {
  const chunks = useMemo(() => {
    if (!words || words.length === 0) return [];
    const res: Word[][] = [];
    let currentChunk: Word[] = [];

    for (let i = 0; i < words.length; i++) {
      const w = words[i];
      currentChunk.push(w);
      const endsWithPunct = /[.,!?;:]$/.test(w.text.trim());
      if (currentChunk.length >= 4 || (currentChunk.length >= 3 && endsWithPunct) || i === words.length - 1) {
        res.push(currentChunk);
        currentChunk = [];
      }
    }
    return res;
  }, [words]);

  if (chunks.length === 0) return null;

  // Find active chunk
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
          const cleanText = w.text.toLowerCase();
          const displayText = idx === 0 ? cleanText.charAt(0).toUpperCase() + cleanText.slice(1) : cleanText;

          return (
            <span
              key={idx}
              style={{
                fontFamily: "'Inter', sans-serif",
                fontSize: 46,
                fontWeight: 600,
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

  // Background Drifting shapes when no b-roll
  const drift1X = interpolate(frame, [0, durationInFrames], [50, 90]);
  const drift1Y = interpolate(frame, [0, durationInFrames], [200, 240]);
  const drift2X = interpolate(frame, [0, durationInFrames], [700, 650]);
  const drift2Y = interpolate(frame, [0, durationInFrames], [1200, 1160]);

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
    const motion = scene.motion || 'title';
    const baseColor = isMaroon ? BRAND.cream : BRAND.ink;
    const accentColor = isMaroon ? BRAND.blush : BRAND.rust;

    if (motion === 'closing') {
      const closingOpacity = interpolate(frame, [0, 20], [0, 1], { extrapolateRight: 'clamp' });
      return (
        <div style={{ opacity: closingOpacity, textAlign: 'center' }}>
          {renderFormattedHeadline(scene.onScreen, BRAND.cream, BRAND.blush, 88)}
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

    if (motion === 'stat') {
      // Check for number in onScreen text
      const numMatch = scene.onScreen.match(/(?<![\w.])[-−]?\d+(?:[.,]\d+)?\s?%?/);
      if (numMatch) {
        const fullNumStr = numMatch[0].trim();
        const rawNum = parseFloat(fullNumStr.replace('%', ''));
        const hasPct = fullNumStr.includes('%');
        // Count up from 0 to rawNum over 20 frames
        const currentCount = interpolate(frame, [0, 20], [0, rawNum], {
          extrapolateRight: 'clamp',
        });
        const displayCount = rawNum % 1 !== 0 ? currentCount.toFixed(1) : Math.round(currentCount);
        const restOfText = scene.onScreen.replace(numMatch[0], '').replace(/\*/g, '').trim();

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
              {displayCount}{hasPct ? '%' : ''}
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
      return renderFormattedHeadline(scene.onScreen, baseColor, accentColor, 88);
    }

    if (motion === 'list') {
      const items = scene.onScreen.split(/\s+[·;]\s+/);
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
            {scene.onScreen.replace(/\*/g, '')}
          </div>
          {citation && (
            <div
              style={{
                fontFamily: "'Inter', sans-serif",
                fontSize: 26,
                fontWeight: 500,
                color: BRAND.clay,
              }}
            >
              {citation}
            </div>
          )}
        </div>
      );
    }

    // Default: 'title' motion
    return renderFormattedHeadline(scene.onScreen, baseColor, accentColor, 88);
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
          {/* Procedural drifting blush circles */}
          <div
            style={{
              position: 'absolute',
              left: `${drift1X}px`,
              top: `${drift1Y}px`,
              width: 450,
              height: 450,
              borderRadius: '50%',
              backgroundColor: BRAND.blush,
              opacity: 0.25,
              filter: 'blur(60px)',
              pointerEvents: 'none',
            }}
          />
          <div
            style={{
              position: 'absolute',
              left: `${drift2X}px`,
              top: `${drift2Y}px`,
              width: 550,
              height: 550,
              borderRadius: '50%',
              backgroundColor: BRAND.blush,
              opacity: 0.20,
              filter: 'blur(70px)',
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
        sceneTimeMs={sceneTimeMs}
        isMaroon={isMaroon}
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

      {scene.kind === 'claim' && citation && (
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
          {citation}
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

  // Calculate sequence ranges
  let accumulatedFrames = 0;
  const sequences = scenes.map(s => {
    const durationFrames = Math.ceil(((s.durationMs + 200) / 1000) * fps);
    const startFrame = accumulatedFrames;
    accumulatedFrames += durationFrames;
    return {
      scene: s,
      startFrame,
      durationFrames,
    };
  });

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
