import React from 'react';
import { Composition } from 'remotion';
import { Reel } from './Reel';
import { ReelProps } from './types';

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
      kind: 'brand',
      claimIds: [],
    }
  ],
  citation: 'Hall & Kahan (2018)',
  draft: true,
  musicSrc: 'http://localhost:8002/assets/music/calm-1.mp3',
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
          totalFrames += Math.ceil(((s.durationMs + 400) / 1000) * fps);
        }
        totalFrames += 45; // 45 frames of outro
        return {
          durationInFrames: Math.max(totalFrames, 90),
        };
      }}
    />
  );
};
