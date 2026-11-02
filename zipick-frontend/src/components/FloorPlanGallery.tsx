import { useEffect, useId, useRef, useState } from 'react';
import styles from './FloorPlanGallery.module.css';

interface PlanFile {
  id: string;
  name?: string | null;
  type: string;
  sourceUrl: string;
  previewUrl: string;
}

// 서버의 이미지 지원 형식과 동일하다. URL은 분류에만 사용하며 원본 경로는 바꾸지 않는다.
function isImage(file: PlanFile) {
  return /\.(?:png|jpe?g|gif|webp)(?:[?&# ].*)?$/i.test(`${file.name ?? ''} ${file.sourceUrl}`);
}

function PlanImage({ file, large = false }: { file: PlanFile; large?: boolean }) {
  const [status, setStatus] = useState<'loading' | 'ready' | 'error'>('loading');
  return <div className={`${styles.image} ${large ? styles.largeImage : ''}`} aria-busy={status === 'loading'}>
    {status !== 'error' && <img src={file.previewUrl} alt={file.name || '평면도·도면'} onLoad={() => setStatus('ready')} onError={() => setStatus('error')} />}
    {status === 'loading' && <span className={styles.imageMessage} role="status">불러오는 중…</span>}
    {status === 'error' && <span className={styles.imageMessage} role="status">이미지를 불러오지 못했어요.{large && <><br />원본 파일을 확인해 주세요.</>}</span>}
  </div>;
}

export default function FloorPlanGallery({ files }: { files: PlanFile[] }) {
  const titleId = useId();
  const trackRef = useRef<HTMLDivElement>(null);
  const dialogRef = useRef<HTMLDialogElement>(null);
  const closeRef = useRef<HTMLButtonElement>(null);
  const openerRef = useRef<HTMLButtonElement | null>(null);
  const [selected, setSelected] = useState<string | null>(null);
  const images = files.filter(isImage);
  const index = images.findIndex((file) => file.id === selected);
  const active = images[index];
  const open = !!active;

  useEffect(() => {
    if (!open) return;
    const dialog = dialogRef.current!;
    const opener = openerRef.current;
    const overflow = document.body.style.overflow;
    const padding = document.body.style.paddingRight;
    const gap = window.innerWidth - document.documentElement.clientWidth;
    document.body.style.paddingRight = `${parseFloat(getComputedStyle(document.body).paddingRight) + gap}px`;
    document.body.style.overflow = 'hidden';
    dialog.showModal();
    closeRef.current?.focus();
    return () => {
      dialog.close();
      document.body.style.overflow = overflow;
      document.body.style.paddingRight = padding;
      if (opener?.isConnected) opener.focus({ preventScroll: true });
    };
  }, [open]);

  const move = (direction: number) => setSelected(images[(index + direction + images.length) % images.length].id);
  const scroll = (direction: number) => {
    const track = trackRef.current;
    if (!track) return;
    const step = (track.firstElementChild?.getBoundingClientRect().width ?? 110) + 10;
    track.scrollBy({ left: direction * step, behavior: window.matchMedia('(prefers-reduced-motion: reduce)').matches ? 'instant' : 'smooth' });
  };

  return <section className={styles.section}>
    <div className={styles.sectionHead}><h3>평면도·도면</h3>{images.length > 0 && <span>이미지를 눌러 크게 보기</span>}</div>
    <div className={styles.carousel}>
      {files.length > 1 && <button type="button" className={styles.arrow} aria-label="이전 평면도 카드" onClick={() => scroll(-1)}>‹</button>}
      <div ref={trackRef} className={styles.track} tabIndex={0} role="region" aria-label="평면도·도면 파일 목록">
        {files.map((file) => {
          const content = <>
            {isImage(file) ? <PlanImage file={file} key={file.previewUrl} /> : <span className={styles.fileIcon} aria-hidden="true">▤</span>}
            {!isImage(file) && file.name && <span className={styles.fileName}>{file.name}</span>}
            {!isImage(file) && <span className={styles.fileType}>{file.type === 'SITE_PLAN' ? '배치도' : '평면도'} · 원본 열기 ↗</span>}
          </>;
          return isImage(file)
            ? <button type="button" key={file.id} className={styles.tile} aria-haspopup="dialog" aria-label={`${file.name || '평면도·도면'} 크게 보기`} onClick={(event) => { openerRef.current = event.currentTarget; setSelected(file.id); }}>{content}</button>
            : <a key={file.id} className={styles.tile} href={file.sourceUrl} target="_blank" rel="noreferrer">{content}</a>;
        })}
      </div>
      {files.length > 1 && <button type="button" className={styles.arrow} aria-label="다음 평면도 카드" onClick={() => scroll(1)}>›</button>}
    </div>
    <dialog ref={dialogRef} className={styles.dialog} aria-labelledby={titleId} onClose={() => setSelected(null)} onCancel={() => setSelected(null)} onClick={(event) => { if (event.target === event.currentTarget) setSelected(null); }} onKeyDown={(event) => {
      if (event.key === 'ArrowLeft' || event.key === 'ArrowRight') { event.preventDefault(); if (images.length > 1) move(event.key === 'ArrowLeft' ? -1 : 1); }
      if (event.key === 'Tab') {
        const targets = event.currentTarget.querySelectorAll<HTMLElement>('button:not(:disabled), a[href]');
        const first = targets[0], last = targets[targets.length - 1];
        if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last.focus(); }
        else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first.focus(); }
      }
    }}>
      {active && <div className={styles.dialogContent}>
        <header className={styles.dialogHead}><h2 id={titleId}>{active.type === 'SITE_PLAN' ? '배치도' : '평면도'}</h2><button ref={closeRef} className={styles.close} type="button" aria-label="평면도 미리보기 닫기" onClick={() => setSelected(null)}>×</button></header>
        <PlanImage key={active.previewUrl} file={active} large />
        <div className={styles.navigation}>
          {images.length > 1 && <button type="button" className={styles.arrow} aria-label="이전 평면도 이미지" onClick={() => move(-1)}>‹</button>}
          <span aria-live="polite" aria-atomic="true">{index + 1} / {images.length}</span>
          {images.length > 1 && <button type="button" className={styles.arrow} aria-label="다음 평면도 이미지" onClick={() => move(1)}>›</button>}
        </div>
        <footer className={styles.footer}><span>ESC로 닫기{images.length > 1 && ' · ← → 이미지 이동'}</span><a href={active.sourceUrl} target="_blank" rel="noreferrer">원본 파일 열기 ↗</a></footer>
      </div>}
    </dialog>
  </section>;
}
