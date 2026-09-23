<script lang="ts">
	import { goto } from '$app/navigation';
	import {
		BookOpen,
		CalendarCheck,
		CornerDownLeft,
		FileText,
		Layers,
		Moon,
		Plus,
		Search,
		UserPlus,
		UserRound
	} from '@lucide/svelte';
	import { get } from '$lib/api';
	import { subjects } from '$lib/data.svelte';
	import { score } from '$lib/fuzzy';
	import { recent, type RecentItem } from '$lib/recent';
	import { can, groups, selectGroup, isMulti } from '$lib/session.svelte';
	import { currentTheme, setTheme } from '$lib/theme';
	import { fmtDue } from '$lib/format';
	import { t } from '$lib/i18n/ru';
	import type { Homework, Member } from '$lib/types';
	import { mainNav } from './nav';
	import { palette } from './palette.svelte';

	interface Entry {
		id: string;
		group: string;
		label: string;
		hint?: string;
		icon: typeof Search;
		color?: string;
		run: () => void;
	}

	let dialog: HTMLDialogElement | undefined = $state();
	let inputEl: HTMLInputElement | undefined = $state();
	let active = $state(0);
	let people = $state<Member[]>([]);
	let homework = $state<Homework[]>([]);
	let recentList = $state<RecentItem[]>([]);
	let loaded = false;

	$effect(() => {
		if (!dialog) return;
		if (palette.open && !dialog.open) {
			dialog.showModal();
			active = 0;
			recentList = recent();
			queueMicrotask(() => inputEl?.focus());
			if (!loaded) loadExtra();
		}
		if (!palette.open && dialog.open) dialog.close();
	});

	async function loadExtra() {
		loaded = true;
		const ids = groups().map((g) => g.id);
		const [members, hw] = await Promise.all([
			Promise.all(ids.map((g) => get<Member[]>(`/api/groups/${g}/members`).catch(() => []))),
			get<Homework[]>('/api/homework?view=week').catch(() => [])
		]);
		const flat = members.flat();
		people = flat.filter((m, i) => flat.findIndex((x) => x.userId === m.userId) === i);
		homework = hw;
	}

	function go(path: string) {
		palette.open = false;
		goto(path);
	}

	const all = $derived.by((): Entry[] => {
		const out: Entry[] = [];
		if (can('publish_homework'))
			out.push({
				id: 'a-hw',
				group: 'Действия',
				label: 'Новое задание',
				icon: Plus,
				run: () => go('/homework?new=1')
			});
		if (can('publish_news'))
			out.push({
				id: 'a-news',
				group: 'Действия',
				label: 'Новая новость',
				icon: Plus,
				run: () => go('/news?new=1')
			});
		if (can('create_invites'))
			out.push({
				id: 'a-inv',
				group: 'Действия',
				label: 'Пригласить в группу',
				icon: UserPlus,
				run: () => go('/settings?tab=invites')
			});
		out.push({
			id: 'a-theme',
			group: 'Действия',
			label: 'Сменить тему',
			icon: Moon,
			run: () => {
				const next = { system: 'light', light: 'dark', dark: 'system' } as const;
				setTheme(next[currentTheme()]);
				palette.open = false;
			}
		});
		for (const r of recentList)
			out.push({
				id: `r-${r.type}-${r.id}`,
				group: 'Недавнее',
				label: r.title,
				icon: r.type === 'subject' ? BookOpen : FileText,
				color: r.color,
				run: () => go(r.type === 'subject' ? `/subjects/${r.id}` : `/materials/${r.id}`)
			});
		for (const n of mainNav)
			out.push({
				id: `n-${n.href}`,
				group: 'Разделы',
				label: n.label,
				icon: n.icon,
				run: () => go(n.href)
			});
		for (const s of subjects.list.filter((x) => !x.archived))
			out.push({
				id: `s-${s.id}`,
				group: 'Предметы',
				label: s.name,
				hint: s.teacher,
				icon: BookOpen,
				color: s.color,
				run: () => go(`/subjects/${s.id}`)
			});
		if (isMulti())
			for (const g of groups())
				out.push({
					id: `g-${g.id}`,
					group: 'Группы',
					label: g.name,
					hint: 'переключиться',
					icon: Layers,
					run: () => {
						selectGroup(g.id);
						palette.open = false;
					}
				});
		for (const h of homework)
			out.push({
				id: `h-${h.id}`,
				group: 'Задания',
				label: h.title,
				hint: `${h.subject.name} · ${fmtDue(h.dueAt)}`,
				icon: CalendarCheck,
				color: h.subject.color,
				run: () => go(`/homework/${h.id}`)
			});
		for (const p of people)
			out.push({
				id: `p-${p.userId}`,
				group: 'Люди',
				label: p.displayName,
				hint: p.username ? '@' + p.username : t.roles[p.role],
				icon: UserRound,
				run: () => go('/members')
			});
		return out;
	});

	const results = $derived.by(() => {
		const q = palette.query.trim();
		if (!q) return all.filter((e) => ['Действия', 'Недавнее', 'Разделы'].includes(e.group));
		const scored = all
			.map((e) => ({ e, s: Math.max(score(q, e.label), score(q, e.hint ?? '') * 0.6) }))
			.filter((x) => x.s > 0)
			.sort((a, b) => b.s - a.s)
			.slice(0, 30)
			.map((x) => x.e);
		scored.push({
			id: 'search',
			group: 'Поиск',
			label: `Искать «${q}» везде`,
			icon: Search,
			run: () => go(`/search?q=${encodeURIComponent(q)}`)
		});
		return scored;
	});

	$effect(() => {
		void palette.query;
		active = 0;
	});

	function onkey(e: KeyboardEvent) {
		if (e.key === 'Escape') {
			// Закрываем синхронно: иначе фокус ещё мгновение остаётся в поле и глотает горячие клавиши.
			e.preventDefault();
			palette.open = false;
			inputEl?.blur();
			return;
		}
		if (e.key === 'ArrowDown') {
			active = (active + 1) % results.length;
			e.preventDefault();
		} else if (e.key === 'ArrowUp') {
			active = (active - 1 + results.length) % results.length;
			e.preventDefault();
		} else if (e.key === 'Enter') {
			results[active]?.run();
			e.preventDefault();
		}
		document.getElementById(`pal-${results[active]?.id}`)?.scrollIntoView({ block: 'nearest' });
	}
</script>

<dialog
	bind:this={dialog}
	class="palette"
	aria-label="Командная палитра"
	onclose={() => (palette.open = false)}
	onclick={(e) => e.target === dialog && (palette.open = false)}
>
	{#if palette.open}
		<div class="box">
			<div class="search">
				<Search size={18} />
				<input
					bind:this={inputEl}
					bind:value={palette.query}
					onkeydown={onkey}
					placeholder="Предмет, задание, человек или действие…"
					role="combobox"
					aria-expanded="true"
					aria-controls="pal-list"
					aria-activedescendant={results[active] ? `pal-${results[active].id}` : undefined}
					autocomplete="off"
					spellcheck="false"
				/>
				<kbd>Esc</kbd>
			</div>
			<ul id="pal-list" role="listbox" aria-label="Результаты">
				{#each results as r, i (r.id)}
					{#if i === 0 || results[i - 1].group !== r.group}
						<li class="group" role="presentation">{r.group}</li>
					{/if}
					{@const Icon = r.icon}
					<li
						id="pal-{r.id}"
						role="option"
						aria-selected={i === active}
						class:active={i === active}
						onmousemove={() => (active = i)}
						onclick={() => r.run()}
						onkeydown={() => {}}
					>
						<span class="ic" style:color={r.color}><Icon size={17} /></span>
						<span class="label">{r.label}</span>
						{#if r.hint}<span class="hint">{r.hint}</span>{/if}
						{#if i === active}<CornerDownLeft size={14} class="enter" />{/if}
					</li>
				{/each}
			</ul>
			<footer class="small faint">
				<span><kbd>↑</kbd><kbd>↓</kbd> выбор</span><span><kbd>Enter</kbd> открыть</span><span
					><kbd>?</kbd> все сочетания</span
				>
			</footer>
		</div>
	{/if}
</dialog>

<style>
	.palette {
		width: min(620px, calc(100vw - 24px));
		margin: 12vh auto auto;
		padding: 0;
		border: 0;
		border-radius: var(--r-xl);
		background: var(--surface);
		color: var(--text);
		box-shadow: var(--shadow-3);
		overflow: hidden;
	}
	.palette[open] {
		animation: pop 180ms var(--ease);
	}
	.palette::backdrop {
		background: var(--overlay);
		animation: fade 180ms var(--ease);
	}
	.box {
		display: flex;
		flex-direction: column;
		max-height: min(70dvh, 560px);
	}
	.search {
		display: flex;
		align-items: center;
		gap: 10px;
		padding: 14px 16px;
		border-bottom: 1px solid var(--border);
		color: var(--text-3);
	}
	.search input {
		flex: 1;
		border: 0;
		outline: none;
		background: transparent;
		font-size: 16px;
		color: var(--text);
	}
	ul {
		list-style: none;
		margin: 0;
		padding: 6px;
		overflow-y: auto;
	}
	.group {
		padding: 10px 10px 4px;
		font-size: 11.5px;
		font-weight: 600;
		letter-spacing: 0.05em;
		text-transform: uppercase;
		color: var(--text-3);
	}
	[role='option'] {
		display: flex;
		align-items: baseline;
		line-height: 1.35;
		gap: 10px;
		padding: 9px 10px;
		border-radius: var(--r-s);
		cursor: pointer;
	}
	[role='option'].active {
		background: var(--accent-soft);
	}
	.ic {
		display: grid;
		place-items: center;
		align-self: center;
		color: var(--text-2);
	}
	.label {
		font-weight: 520;
		white-space: nowrap;
		overflow: hidden;
		text-overflow: ellipsis;
	}
	.hint {
		flex: 1;
		min-width: 0;
		color: var(--text-3);
		font-size: 13px;
		white-space: nowrap;
		overflow: hidden;
		text-overflow: ellipsis;
	}
	li :global(.enter) {
		align-self: center;
		margin-left: auto;
		color: var(--accent);
		flex: none;
	}
	footer {
		display: flex;
		gap: 16px;
		padding: 10px 16px;
		border-top: 1px solid var(--border);
	}
	kbd {
		display: inline-grid;
		place-items: center;
		min-width: 20px;
		height: 20px;
		padding: 0 5px;
		margin-right: 3px;
		border: 1px solid var(--border-strong);
		border-bottom-width: 2px;
		border-radius: 5px;
		font: 600 11px var(--font);
		color: var(--text-2);
	}
	@keyframes pop {
		from {
			opacity: 0;
			transform: translateY(-8px) scale(0.98);
		}
	}
	@keyframes fade {
		from {
			opacity: 0;
		}
	}
	@media (max-width: 640px) {
		.palette {
			margin-top: 8px;
		}
		footer {
			display: none;
		}
	}
</style>
