import React from 'react';
import { Composition } from 'remotion';
import { Reel } from './Reel.js';
import { ReelProps } from './types.js';

const defaultProps: ReelProps = {
  scenes: [
    {
      n: 1,
      onScreen: 'Why does the weight return?',
      motion: 'title',
      audioSrc: '',
      durationMs: 4000,
      words: [{ text: 'Why', startMs: 0, endMs: 500 }],
      brollSrc: null,
    }
  ],
  citation: 'Hall & Kahan (2018)',
  draft: true,
};

export const Root: React.FC = () => {
  return (
    <Composition
      id="SteerReel"
      component={Reel}
      durationInFrames={300}
      fps={30}
      width={1080}
      height={1920}
      defaultProps={defaultProps}
      calculateMetadata={({ props }) => {
        const fps = 30;
        let totalFrames = 0;
        for (const s of props.scenes) {
          const baseFrames = Math.round((s.durationMs / 1000) * fps);
          totalFrames += baseFrames + 12; // 0.4s padding
        }
        // Add 1.5s outro (45 frames)
        totalFrames += 45;
        return {
          durationInFrames: Math.max(totalFrames, 90),
        };
      }}
    />
  );
};
