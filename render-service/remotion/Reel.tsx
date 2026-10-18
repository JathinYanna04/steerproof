import React from 'react';
import {
  AbsoluteFill,
  Sequence,
  Audio,
  interpolate,
  useCurrentFrame,
  useVideoConfig
} from 'remotion';
import { ReelProps, SceneData, Word } from './types.js';
import { brand } from '../src/brand.js';

const BRAND = brand;


// Subtitle component: displays current window of 3-5 words
const Subtitles: React.FC<{ words: Word[]; sceneTimeMs: number }> = ({ words, sceneTimeMs }) => {
  if (!words || words.length === 0) return null;

  // Find active word index
  let activeIdx = words.findIndex(w => sceneTimeMs >= w.startMs && sceneTimeMs <= w.endMs);
  if (activeIdx === -1) {
    // If between words, find next word or last word
    const nextIdx = words.findIndex(w => sceneTimeMs < w.startMs);
    activeIdx = nextIdx !== -1 ? Math.max(0, nextIdx - 1) : words.length - 1;
  }

  // Window of 4 words centered around activeIdx
  const windowSize = 4;
  const startWindow = Math.max(0, Math.min(words.length - windowSize, activeIdx - 1));
  const visibleWords = words.slice(startWindow, startWindow + windowSize);

  return (
    <div
      style={{
        position: 'absolute',
        bottom: 220,
        left: 0,
        right: 0,
        display: 'flex',
        justifyContent: 'center',
        padding: '0 60px',
        zIndex: 10,
      }}
    >
      <div
        style={{
          background: 'rgba(248, 242, 235, 0.94)',
          borderRadius: 24,
          padding: '16px 32px',
          boxShadow: '0 8px 30px rgba(0,0,0,0.06)',
          display: 'flex',
          gap: 12,
          flexWrap: 'wrap',
          justifyContent: 'center',
          maxWidth: 900,
        }}
      >
        {visibleWords.map((w, i) => {
          const isCurrent = sceneTimeMs >= w.startMs && sceneTimeMs <= w.endMs;
          return (
            <span
              key={i}
              style={{
                fontFamily: "'Inter', sans-serif",
                fontSize: 42,
                fontWeight: isCurrent ? 700 : 500,
                color: isCurrent ? BRAND.rust : BRAND.ink,
                transition: 'color 0.1s ease',
              }}
            >
              {w.text}
            </span>
          );
        })}
      </div>
    </div>
  );
};

// Scene Renderer
const SceneView: React.FC<{
  scene: SceneData;
  durationInFrames: number;
  citation: string;
}> = ({ scene, durationInFrames, citation }) => {
  const frame = useCurrentFrame();
  const { fps } = useVideoConfig();
  const sceneTimeMs = (frame / fps) * 1000;

  const isClosing = scene.motion === 'closing';
  const bgColor = isClosing ? BRAND.maroon : BRAND.cream;
  const textColor = isClosing ? BRAND.cream : BRAND.ink;

  // Title fade and rise over 12 frames
  const opacity = interpolate(frame, [0, 12], [0, 1], { extrapolateRight: 'clamp' });
  const translateY = interpolate(frame, [0, 12], [30, 0], { extrapolateRight: 'clamp' });

  // Format headline with italics if present
  const renderFormattedText = (text: string) => {
    const parts = text.split(/(\*[^*]+\*)/g);
    return parts.map((part, i) => {
      if (part.startsWith('*') && part.endsWith('*')) {
        return (
          <span
            key={i}
            style={{
              fontStyle: 'italic',
              color: isClosing ? BRAND.blush : BRAND.rust,
            }}
          >
            {part.slice(1, -1)}
          </span>
        );
      }
      return part;
    });
  };

  return (
    <AbsoluteFill
      style={{
        backgroundColor: bgColor,
        padding: '120px 80px',
        display: 'flex',
        flexDirection: 'column',
        justifyContent: 'center',
        fontFamily: "'Inter', sans-serif",
      }}
    >
      {/* Background shape */}
      {!isClosing && (
        <div
          style={{
            position: 'absolute',
            top: 200,
            right: -100,
            width: 500,
            height: 500,
            borderRadius: '50%',
            background: 'radial-gradient(circle, rgba(230, 203, 189, 0.35) 0%, rgba(251, 248, 243, 0) 70%)',
            pointerEvents: 'none',
          }}
        />
      )}

      {/* Main Motion Content */}
      <div
        style={{
          opacity,
          transform: `translateY(${translateY}px)`,
          textAlign: 'center',
          maxWidth: 920,
          margin: '0 auto',
        }}
      >
        <div
          style={{
            fontFamily: "'Instrument Serif', serif",
            fontSize: isClosing ? 82 : 92,
            lineHeight: 1.15,
            color: textColor,
            marginBottom: 40,
          }}
        >
          {renderFormattedText(scene.onScreen)}
        </div>

        {scene.motion === 'stat' && (
          <div
            style={{
              display: 'inline-block',
              background: isClosing ? 'rgba(255,255,255,0.08)' : BRAND.softPaper,
              border: `1.5px solid ${isClosing ? 'rgba(255,255,255,0.12)' : 'rgba(61, 20, 26, 0.08)'}`,
              borderRadius: 20,
              padding: '20px 40px',
              color: isClosing ? BRAND.cream : BRAND.rust,
              fontSize: 32,
              fontWeight: 600,
            }}
          >
            Clinical finding
          </div>
        )}

        {scene.motion === 'quote' && (
          <div
            style={{
              background: BRAND.softPaper,
              borderLeft: `4px solid ${BRAND.rust}`,
              borderRadius: '0 16px 16px 0',
              padding: '24px 36px',
              fontSize: 32,
              color: BRAND.clay,
              fontStyle: 'italic',
              textAlign: 'left',
              marginTop: 20,
            }}
          >
            Medical literature review
          </div>
        )}
      </div>

      {/* Subtitles Layer */}
      <Subtitles words={scene.words} sceneTimeMs={sceneTimeMs} />

      {/* Audio Sequence */}
      {scene.audioSrc && <Audio src={scene.audioSrc} />}
    </AbsoluteFill>
  );
};

export const Reel: React.FC<ReelProps> = ({ scenes, citation, draft, musicSrc }) => {
  const { fps } = useVideoConfig();

  let accumulatedFrames = 0;
  const paddedScenes = scenes.map(s => {
    // scene audio duration in frames + 12 frames padding (0.4s)
    const baseFrames = Math.round((s.durationMs / 1000) * fps);
    const durationFrames = baseFrames + 12;
    const startFrame = accumulatedFrames;
    accumulatedFrames += durationFrames;
    return {
      scene: s,
      startFrame,
      durationFrames,
    };
  });

  return (
    <AbsoluteFill style={{ backgroundColor: BRAND.cream }}>
      {/* Background scenes */}
      {paddedScenes.map(({ scene, startFrame, durationFrames }, idx) => (
        <Sequence
          key={idx}
          from={startFrame}
          durationInFrames={durationFrames}
        >
          <SceneView
            scene={scene}
            durationInFrames={durationFrames}
            citation={citation}
          />
        </Sequence>
      ))}

      {/* Top persistent bar: Brand Logo & Watermark */}
      <div
        style={{
          position: 'absolute',
          top: 70,
          left: 80,
          right: 80,
          display: 'flex',
          justifyContent: 'space-between',
          alignItems: 'center',
          zIndex: 20,
        }}
      >
        <div
          style={{
            fontFamily: "'Instrument Serif', serif",
            fontSize: 48,
            fontStyle: 'italic',
            fontWeight: 700,
            color: BRAND.rust,
            letterSpacing: -1,
          }}
        >
          steer
        </div>

        {draft && (
          <div
            style={{
              background: 'rgba(61, 20, 26, 0.08)',
              border: '1px solid rgba(61, 20, 26, 0.15)',
              borderRadius: 30,
              padding: '8px 20px',
              fontSize: 22,
              fontWeight: 600,
              color: BRAND.clay,
              textTransform: 'lowercase',
              letterSpacing: 0.5,
            }}
          >
            draft · pending doctor review
          </div>
        )}
      </div>

      {/* Bottom persistent citation */}
      {citation && (
        <div
          style={{
            position: 'absolute',
            bottom: 60,
            left: 80,
            right: 80,
            textAlign: 'center',
            fontSize: 24,
            color: BRAND.clay,
            fontFamily: "'Inter', sans-serif",
            opacity: 0.8,
            zIndex: 20,
          }}
        >
          {citation}
        </div>
      )}
    </AbsoluteFill>
  );
};
