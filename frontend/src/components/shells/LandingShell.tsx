import { useTheme } from '../../contexts/ThemeContext';
import { PublicShell } from './PublicShell';

// LandingShell — PublicShell variant follows the active theme. Toggling
// the moon/sun in the topbar flips the entire landing (dark ↔ light)
// because users naturally expect that behavior.
export function LandingShell() {
  const { theme } = useTheme();
  return <PublicShell variant={theme === 'dark' ? 'dark' : 'light'} />;
}
