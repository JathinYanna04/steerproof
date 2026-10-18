import React from 'react';
import { Composition } from 'remotion';
import { Reel } from './Reel';
import { ReelProps, sceneFrames, OUTRO_FRAMES } from './types';

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
  citation: '',
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
          totalFrames += sceneFrames(s, fps);
        }
        totalFrames += OUTRO_FRAMES; // 1.0s outro, held by the last scene
        return {
          durationInFrames: Math.max(totalFrames, 90),
        };
      }}
    />
  );
};
