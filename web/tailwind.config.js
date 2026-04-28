/** @type {import('tailwindcss').Config} */
export default {
  content: ["./index.html", "./src/**/*.{vue,js,ts,jsx,tsx}"],
  theme: {
    extend: {
      colors: {
        ink: "#1b1a17",
        sun: "#f5b318",
        ocean: "#0f766e",
        fog: "#f7f4ee"
      },
      boxShadow: {
        card: "0 20px 40px rgba(26, 31, 44, 0.12)"
      },
      keyframes: {
        rise: {
          "0%": { opacity: 0, transform: "translateY(20px)" },
          "100%": { opacity: 1, transform: "translateY(0)" }
        }
      },
      animation: {
        rise: "rise 0.5s ease-out"
      }
    }
  },
  plugins: []
};
