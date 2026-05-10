import { Link } from '@tanstack/react-router';
import { css } from 'styled-system/css';
import { PAGE_MAX_WIDTH } from './Page';

const footerOuterStyles = css({
  width: '100%',
  bg: 'bg',
  borderTop: '1px solid token(colors.gridLine)',
});

const footerInnerStyles = css({
  width: '100%',
  maxWidth: PAGE_MAX_WIDTH,
  margin: '0 auto',
  paddingInline: { base: '16px', md: '20px' },
  paddingBlock: { base: '12px', md: '16px' },
  display: 'flex',
  flexWrap: 'wrap',
  alignItems: 'center',
  justifyContent: { base: 'center', md: 'space-between' },
  gap: { base: '8px', md: '16px' },
  fontSize: 'sm',
  color: 'fgMuted',
});

const linkListStyles = css({
  display: 'flex',
  flexWrap: 'wrap',
  gap: { base: '12px', md: '20px' },
  alignItems: 'center',
});

const linkStyles = css({
  color: 'fgMuted',
  textDecoration: 'none',
  _hover: { color: 'fg' },
  _focusVisible: {
    outline: '2px solid token(colors.focusRing)',
    outlineOffset: '2px',
    borderRadius: '2px',
  },
});

const copyStyles = css({
  margin: 0,
});

export function Footer() {
  return (
    <footer className={footerOuterStyles}>
      <div className={footerInnerStyles}>
        <p className={copyStyles}>© WordSparrow</p>
        <nav className={linkListStyles} aria-label="Liens légaux">
          <Link to="/confidentialite" className={linkStyles}>
            Confidentialité
          </Link>
          <Link to="/mentions-legales" className={linkStyles}>
            Mentions légales
          </Link>
          <a
            href="mailto:contact@wordsparrow.io"
            className={linkStyles}
          >
            Contact
          </a>
        </nav>
      </div>
    </footer>
  );
}
