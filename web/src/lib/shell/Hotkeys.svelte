<script lang="ts">
	import { goto } from '$app/navigation';
	import { page } from '$app/state';
	import { can } from '$lib/session.svelte';
	import Modal from '$lib/ui/Modal.svelte';
	import { openPalette, palette } from './palette.svelte';

	let help = $state(false);
	let pendingG = 0;

	const jumps: Record<string, string> = {
		h: '/',
		n: '/news',
		d: '/homework',
		m: '/materials',
		s: '/subjects',
		u: '/members',
		o: '/settings',
		p: '/profile',
		b: '/notifications'
	};

	function typing(t: EventTarget | null): boolean {
		const el = t as HTMLElement | null;
		if (!el || !el.isConnected || el.closest('dialog:not([open])')) return false;
		return el.isContentEditable || ['INPUT', 'TEXTAREA', 'SELECT'].includes(el.tagName);
	}

	function newItem() {
		const p = page.url.pathname;
		if (p.startsWith('/news') && can('publish_news')) goto('/news?new=1');
		else if ((p.startsWith('/homework') || p === '/') && can('publish_homework'))
			goto('/homework?new=1');
		else if (can('publish_homework')) goto('/homework?new=1');
		else if (can('publish_news')) goto('/news?new=1');
	}

	function onkeydown(e: KeyboardEvent) {
		if ((e.metaKey || e.ctrlKey) && e.key.toLowerCase() === 'k') {
			e.preventDefault();
			if (palette.open) palette.open = false;
			else openPalette();
			return;
		}
		if (e.metaKey || e.ctrlKey || e.altKey || typing(e.target)) return;
		// Состояние берём из DOM: событие close у диалога приходит с задержкой.
		if (document.querySelector('dialog[open]')) return;
		const now = Date.now();
		if (pendingG && now - pendingG < 1200 && jumps[e.key]) {
			pendingG = 0;
			e.preventDefault();
			goto(jumps[e.key]);
			return;
		}
		pendingG = 0;
		if (e.key === 'g') pendingG = now;
		else if (e.key === '/') {
			e.preventDefault();
			goto('/search');
		} else if (e.key === 'n') {
			e.preventDefault();
			newItem();
		} else if (e.key === '?') {
			help = true;
		}
	}

	const rows: [string, string][] = [
		['Ctrl/⌘ + K', 'Командная палитра'],
		['/', 'Поиск'],
		['n', 'Новая запись (ДЗ или новость)'],
		['g h', 'Сегодня'],
		['g d', 'Домашние задания'],
		['g m', 'Материалы'],
		['g n', 'Новости'],
		['g s', 'Предметы'],
		['g u', 'Участники'],
		['g o', 'Настройки'],
		['g p', 'Профиль'],
		['g b', 'Уведомления'],
		['?', 'Эта подсказка']
	];
</script>

<svelte:window {onkeydown} />

<Modal bind:open={help} title="Горячие клавиши">
	<table class="keys">
		<tbody>
			{#each rows as [k, v] (k)}
				<tr
					><td
						>{#each k.split(' ') as part, i (i)}<kbd>{part}</kbd>{/each}</td
					><td>{v}</td></tr
				>
			{/each}
		</tbody>
	</table>
</Modal>

<style>
	.keys {
		width: 100%;
		border-collapse: collapse;
	}
	td {
		padding: 8px 4px;
		border-bottom: 1px solid var(--border);
	}
	td:first-child {
		width: 45%;
	}
	kbd {
		display: inline-grid;
		place-items: center;
		min-width: 24px;
		height: 24px;
		padding: 0 6px;
		margin-right: 4px;
		border: 1px solid var(--border-strong);
		border-bottom-width: 2px;
		border-radius: 6px;
		font: 600 12px var(--font);
	}
</style>
