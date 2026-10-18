/** @type {import('next').NextConfig} */
const nextConfig = {
  reactStrictMode: false,
  // Lets a dev server or a verification build run beside a live `next start` without clobbering .next
  distDir: process.env.NEXT_DIST_DIR || ".next",
};

export default nextConfig;
