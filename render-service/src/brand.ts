export const brand = {
  cream: "#F5EBDF", // main background on every post
  rust: "#C04E2A", // headlines, key words, links
  maroon: "#661414", // full-bleed bold/emotional slides
  ink: "#351D1D", // body text on cream
  clay: "#B58777", // secondary text, captions, labels
  blush: "#E6CBBD", // dividers, card borders, subtle fills
  softPaper: "#F8F2EB", // cards/boxes on cream
} as const;

export function getCssVariables(): string {
  return `
    :root {
      --cream: ${brand.cream};
      --rust: ${brand.rust};
      --maroon: ${brand.maroon};
      --ink: ${brand.ink};
      --clay: ${brand.clay};
      --blush: ${brand.blush};
      --soft-paper: ${brand.softPaper};
    }
  `;
}
