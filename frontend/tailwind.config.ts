import type { Config } from "tailwindcss";

const config: Config = {
  content: [
    "./pages/**/*.{js,ts,jsx,tsx,mdx}",
    "./components/**/*.{js,ts,jsx,tsx,mdx}",
    "./app/**/*.{js,ts,jsx,tsx,mdx}",
  ],
  theme: {
    extend: {
      colors: {
        cream: "#F5EBDF",
        rust: "#C04E2A",
        maroon: "#661414",
        ink: "#351D1D",
        clay: "#B58777",
        blush: "#E6CBBD",
        softPaper: "#F8F2EB",
      },
      fontFamily: {
        serif: ['"Instrument Serif"', "serif"],
        sans: ["Inter", "sans-serif"],
        hand: ["Caveat", "cursive"],
      },
    },
  },
  plugins: [],
};
export default config;
