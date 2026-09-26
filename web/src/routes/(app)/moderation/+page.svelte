<script lang="ts">
	import { page } from '$app/state';
	import { Eye, EyeOff, Flag, MessageSquare, ShieldCheck, Trash2 } from '@lucide/svelte';
	import { get, post, qs } from '$lib/api';
	import { fmtAgo, plural } from '$lib/format';
	import { fly, slide, stagger } from '$lib/motion';
	import { offline } from '$lib/offline/engine';
	import { can } from '$lib/session.svelte';
	import { toast, toastError } from '$lib/toasts.svelte';
	import { canModerate, moderation, refreshModeration } from '$lib/moderation.svelte';
	import { KIND, remove, setHidden } from '$lib/content/moderate';
	import type { Material, ModLogEntry, ModReport, ModTarget } from '$lib/types';
	import { materialActions } from '$lib/content/materialActions';
	import MaterialRow from '$lib/content/MaterialRow.svelte';
	import Author from '$lib/content/Author.svelte';
	import Button from '$lib/ui/Button.svelte';
	import Empty from '$lib/ui/Empty.svelte';
	import Skeleton from '$lib/ui/Skeleton.svelte';
	import { ask } from '$lib/ui/ask.svelte';

	type Tab = 'queue' | 'comments' | 'hidden' | 'log';
	const tabs = $derived(
		[
			{ value: 'queue' as Tab, label: 'Очередь', count: moderation.reports + moderation.pending },
			{ value: 'comments' as Tab, label: 'Комментарии', count: 0 },
			{ value: 'hidden' as Tab, label: 'Скрытое', count: 0 },
			{ value: 'log' as Tab, label: 'Журнал действий', count: 0, hide: !can('view_audit') }
		].filter((t) => !t.hide)
	);
	const asked = $derived(page.url.searchParams.get('tab') as Tab | null);
	const tab = $derived(tabs.some((t) => t.value === asked) ? asked! : 'queue');

	let reports = $state<ModReport[] | null>(null);
	let pending = $state<Material[] | null>(null);
	let comments = $state<ModTarget[] | null>(null);
	let moreComments = $state(false);
	let hidden = $state<ModTarget[] | null>(null);
	let log = $state<ModLogEntry[] | null>(null);
	let moreLog = $state(false);

	async function loadQueue() {
		[reports, pending] = await Promise.all([
			get<ModReport[]>('/api/moderation/reports'),
			get<Material[]>('/api/materials/pending')
		]);
		moderation.reports = reports.length;
		moderation.pending = pending.length;
	}

	async function loadComments(before?: number) {
		const list = await get<ModTarget[]>(`/api/moderation/comments${qs({ before })}`);
		comments = before ? [...(comments ?? []), ...list] : list;
		moreComments = list.length === 30;
	}

	async function loadHidden() {
		hidden = await get<ModTarget[]>('/api/moderation/hidden');
	}

	async function loadLog(before?: number) {
		const list = await get<ModLogEntry[]>(`/api/moderation/log${qs({ before })}`);
		log = before ? [...(log ?? []), ...list] : list;
		moreLog = list.length === 50;
	}

	function reload(t: Tab) {
		const load = { queue: loadQueue, comments: loadComments, hidden: loadHidden, log: loadLog }[t];
		load().catch(toastError);
	}

	// Новые жалобы и комментарии приходят сами (живые обновления) — вкладка перечитывает себя.
	$effect(() => {
		void offline.version;
		reload(tab);
	});

	/** «Комментарий к новости», «Задание» — подпись карточки. */
	function kind(t: ModTarget): string {
		if (t.type !== 'comment') return KIND[t.type][0].toUpperCase() + KIND[t.type].slice(1);
		const to = { post: 'новости', homework: 'заданию', material: 'материалу' };
		return `Комментарий к ${t.parentType ? to[t.parentType] : 'записи'}`;
	}

	async function resolve(r: ModReport, action: 'hide' | 'delete' | 'dismiss') {
		const what = KIND[r.target.type];
		if (
			action === 'delete' &&
			!(await ask(`Удалить ${what} насовсем? Это нельзя отменить.`, {
				ok: 'Удалить',
				danger: true
			}))
		)
			return;
		try {
			await post('/api/moderation/reports/resolve', {
				type: r.target.type,
				id: r.target.id,
				action
			});
			const done = { hide: 'Скрыто', delete: 'Удалено', dismiss: 'Оставлено как есть' };
			toast(done[action], 'ok');
			reports = (reports ?? []).filter((x) => x !== r);
			moderation.reports = reports.length;
		} catch (e) {
			toastError(e);
		}
	}

	async function toggle(t: ModTarget) {
		try {
			await setHidden(t.type, t.id, !t.hidden);
			toast(t.hidden ? 'Снова видно всем' : 'Скрыто', 'ok');
			t.hidden = !t.hidden;
			if (tab === 'hidden') hidden = (hidden ?? []).filter((x) => x !== t);
		} catch (e) {
			toastError(e);
		}
	}

	async function erase(t: ModTarget) {
		if (
			!(await ask(`Удалить ${KIND[t.type]} насовсем? Это нельзя отменить.`, {
				ok: 'Удалить',
				danger: true
			}))
		)
			return;
		try {
			await remove(t.type, t.id);
			toast('Удалено', 'ok');
			comments = comments?.filter((x) => x !== t) ?? null;
			hidden = hidden?.filter((x) => x !== t) ?? null;
		} catch (e) {
			toastError(e);
		}
	}

	/** Что сделали — без «скрыл/скрыла»: имя стоит отдельно. */
	const ACTION: Record<string, string> = {
		'news.hide': 'новость скрыта',
		'news.unhide': 'новость возвращена',
		'news.delete': 'новость удалена',
		'homework.hide': 'задание скрыто',
		'homework.unhide': 'задание возвращено',
		'homework.delete': 'задание удалено',
		'material.approve': 'материал одобрен',
		'material.reject': 'материал отклонён',
		'material.hide': 'материал скрыт',
		'material.unhide': 'материал возвращён',
		'material.delete': 'материал удалён',
		'comment.hide': 'комментарий скрыт',
		'comment.unhide': 'комментарий возвращён',
		'comment.delete': 'комментарий удалён',
		'report.dismiss': 'жалоба разобрана — оставлено как есть'
	};

	$effect(() => {
		if (!canModerate()) return;
		refreshModeration();
	});
</script>

<svelte:head><title>Модерация · groupbase</title></svelte:head>

<div class="page-head">
	<div>
		<h1>Модерация</h1>
		<p class="muted lead">Жалобы, материалы на проверке, свежие комментарии и всё, что скрыто</p>
	</div>
</div>

{#if !canModerate()}
	<div class="card">
		<Empty
			title="Модерация — для администраторов, модераторов и старост"
			text="Если что-то в группе не так, нажмите «Пожаловаться» в меню записи или у комментария."
		/>
	</div>
{:else}
	<nav class="subtabs" aria-label="Разделы модерации">
		{#each tabs as t (t.value)}
			<a
				href="/moderation?tab={t.value}"
				class:active={t.value === tab}
				aria-current={t.value === tab ? 'page' : undefined}
				data-sveltekit-noscroll
				data-sveltekit-replacestate
				>{t.label}{#if t.count}<span class="count num">{t.count}</span>{/if}</a
			>
		{/each}
	</nav>

	{#if tab === 'queue'}
		{#if reports === null || pending === null}
			<Skeleton lines={4} />
		{:else if reports.length === 0 && pending.length === 0}
			<div class="card calm" in:fly={{ y: 8 }}>
				<span class="calm-icon"><ShieldCheck size={28} /></span>
				<strong>Всё спокойно</strong>
				<span class="muted"
					>Жалоб и материалов на проверке нет. Свежие комментарии — во вкладке «Комментарии».</span
				>
			</div>
		{:else}
			{#if reports.length}
				<h2 class="h">Жалобы · <span class="num">{reports.length}</span></h2>
				<div class="stack-list">
					{#each reports as r, i (r.target.type + r.target.id)}
						<article class="card mod" in:fly={{ y: 10, delay: stagger(i) }} out:slide>
							<header>
								<span class="chip amber"
									><Flag size={13} /> <span class="num">{r.count}</span>
									{plural(r.count, ['жалоба', 'жалобы', 'жалоб'])}</span
								>
								<span class="kind">{kind(r.target)}</span>
								<span class="spacer"></span>
								<span class="faint small">{fmtAgo(r.lastAt)}</span>
							</header>
							{#if r.target.type === 'comment'}
								<p class="text quote">{r.target.text}</p>
								<a class="where small" href={r.target.href}>«{r.target.title}»</a>
							{:else}
								<a class="title" href={r.target.href}>{r.target.title}</a>
								{#if r.target.text}<p class="text">{r.target.text}</p>{/if}
							{/if}
							<div class="by small">
								<Author person={r.target.author} size={22} />
								<span class="faint">{fmtAgo(r.target.createdAt)}</span>
							</div>
							{#if r.reasons.length}
								<ul class="reasons">
									{#each r.reasons as why (why)}<li>{why}</li>{/each}
								</ul>
							{/if}
							<div class="actions">
								<Button size="s" onclick={() => resolve(r, 'hide')}
									><EyeOff size={15} /> Скрыть</Button
								>
								<Button size="s" variant="danger" onclick={() => resolve(r, 'delete')}
									><Trash2 size={15} /> Удалить</Button
								>
								<Button size="s" variant="ghost" onclick={() => resolve(r, 'dismiss')}
									>Оставить как есть</Button
								>
							</div>
						</article>
					{/each}
				</div>
			{/if}
			{#if pending.length}
				<h2 class="h">Материалы на проверке · <span class="num">{pending.length}</span></h2>
				<div class="list">
					{#each pending as m (m.id)}<MaterialRow
							{m}
							showSubject
							actions={materialActions(m, loadQueue)}
						/>{/each}
				</div>
			{/if}
		{/if}
	{:else if tab === 'comments' || tab === 'hidden'}
		{@const list = tab === 'comments' ? comments : hidden}
		{#if list === null}
			<Skeleton lines={4} />
		{:else if list.length === 0}
			<div class="card">
				<Empty
					title={tab === 'comments' ? 'Комментариев пока нет' : 'Ничего не скрыто'}
					text={tab === 'comments'
						? 'Здесь появятся все новые комментарии групп, которые вы модерируете.'
						: 'Скрытые новости, задания, материалы и комментарии можно будет вернуть отсюда.'}
				/>
			</div>
		{:else}
			<div class="stack-list">
				{#each list as t, i (t.type + t.id)}
					<article
						class="card mod"
						class:dim={t.hidden}
						in:fly={{ y: 10, delay: stagger(i) }}
						out:slide
					>
						<header>
							{#if t.type === 'comment'}
								<Author person={t.author} size={24} />
							{:else}
								<span class="kind">{kind(t)}</span>
							{/if}
							<span class="faint small">{fmtAgo(t.createdAt)}</span>
							{#if t.hidden}<span class="chip"><EyeOff size={12} /> скрыто</span>{/if}
						</header>
						{#if t.type === 'comment'}
							<p class="text quote">{t.text}</p>
							<a class="where small" href={t.href}
								><MessageSquare size={13} /> {kind(t).toLowerCase()} «{t.title}»</a
							>
						{:else}
							<a class="title" href={t.href}>{t.title}</a>
							{#if t.text}<p class="text">{t.text}</p>{/if}
							<div class="by small"><Author person={t.author} size={22} /></div>
						{/if}
						<div class="actions">
							<Button size="s" onclick={() => toggle(t)}
								>{#if t.hidden}<Eye size={15} /> Вернуть{:else}<EyeOff size={15} /> Скрыть{/if}</Button
							>
							<Button size="s" variant="danger" onclick={() => erase(t)}
								><Trash2 size={15} /> Удалить</Button
							>
						</div>
					</article>
				{/each}
			</div>
			{#if tab === 'comments' && moreComments}
				<div class="more">
					<Button
						variant="ghost"
						onclick={() => loadComments(comments!.at(-1)!.id).catch(toastError)}
						>Показать ещё</Button
					>
				</div>
			{/if}
		{/if}
	{:else if tab === 'log'}
		{#if log === null}
			<Skeleton lines={5} />
		{:else if log.length === 0}
			<div class="card">
				<Empty
					title="Записей пока нет"
					text="Здесь видно, кто что скрыл, вернул, удалил или одобрил."
				/>
			</div>
		{:else}
			<div class="list">
				{#each log as e (e.id)}
					<div class="list-row log">
						<span><strong>{e.actorName || 'Система'}</strong> · {ACTION[e.action] ?? e.action}</span
						>
						{#if e.title}<span class="small muted what">«{e.title}»</span>{/if}
						<span class="faint small num">{fmtAgo(e.at)}</span>
					</div>
				{/each}
			</div>
			{#if moreLog}
				<div class="more">
					<Button variant="ghost" onclick={() => loadLog(log!.at(-1)!.id).catch(toastError)}
						>Показать ещё</Button
					>
				</div>
			{/if}
		{/if}
	{/if}
{/if}

<style>
	.lead {
		margin-top: 4px;
	}
	.subtabs {
		display: flex;
		gap: 8px;
		margin: 0 calc(-1 * var(--s4)) var(--s4);
		padding: 2px var(--s4);
		overflow-x: auto;
		scrollbar-width: none;
	}
	.subtabs::-webkit-scrollbar {
		display: none;
	}
	.subtabs a {
		flex: none;
		display: inline-flex;
		align-items: center;
		gap: 8px;
		height: 40px;
		padding: 0 18px;
		border: 1px solid var(--border);
		border-radius: var(--r-full);
		color: var(--text-2);
		font-weight: 600;
		text-decoration: none;
		transition:
			background-color var(--dur) var(--ease),
			color var(--dur) var(--ease),
			transform 120ms var(--ease);
	}
	.subtabs a:active {
		transform: scale(0.96);
	}
	.subtabs a.active {
		background: var(--inverse);
		border-color: var(--inverse);
		color: var(--inverse-text);
	}
	.count {
		min-width: 22px;
		height: 22px;
		padding: 0 6px;
		border-radius: 11px;
		background: var(--amber);
		color: var(--bg);
		font-size: 12px;
		line-height: 22px;
		text-align: center;
	}
	.h {
		margin: var(--s5) 0 var(--s3);
		font-size: 17px;
	}
	.stack-list {
		display: flex;
		flex-direction: column;
		gap: var(--s3);
	}
	.mod {
		display: flex;
		flex-direction: column;
		gap: 10px;
		transition: opacity var(--dur) var(--ease);
	}
	.mod.dim {
		opacity: 0.72;
	}
	.mod header {
		display: flex;
		flex-wrap: wrap;
		align-items: center;
		gap: 8px 10px;
	}
	.kind {
		font-size: 13px;
		font-weight: 650;
		color: var(--text-2);
	}
	.title {
		color: var(--text);
		font-size: 18px;
		font-weight: 700;
		letter-spacing: -0.01em;
		overflow-wrap: break-word;
	}
	.text {
		margin: 0;
		color: var(--text-2);
		overflow-wrap: break-word;
	}
	.quote {
		padding: 10px 14px;
		border-left: 3px solid var(--border-strong);
		border-radius: 4px 12px 12px 4px;
		background: var(--surface-2);
		color: var(--text);
		white-space: pre-line;
	}
	.where {
		display: inline-flex;
		align-items: center;
		gap: 6px;
		color: var(--text-2);
	}
	.by {
		display: flex;
		align-items: center;
		gap: 10px;
	}
	.reasons {
		margin: 0;
		padding: 0;
		list-style: none;
		display: flex;
		flex-direction: column;
		gap: 6px;
	}
	.reasons li {
		padding: 8px 12px;
		border-radius: 12px;
		background: color-mix(in srgb, var(--amber, #e8a33d) 14%, transparent);
		font-size: 14px;
	}
	.reasons li::before {
		content: '«';
	}
	.reasons li::after {
		content: '»';
	}
	.actions {
		display: flex;
		flex-wrap: wrap;
		gap: 8px;
		margin-top: 2px;
	}
	.calm {
		display: flex;
		flex-direction: column;
		align-items: center;
		gap: 6px;
		padding: var(--s6) var(--s4);
		text-align: center;
	}
	.calm-icon {
		display: grid;
		place-items: center;
		width: 60px;
		height: 60px;
		margin-bottom: 6px;
		border-radius: 50%;
		background: color-mix(in srgb, var(--ok) 14%, transparent);
		color: var(--ok);
		animation: calm 2.4s ease-in-out infinite;
	}
	@keyframes calm {
		50% {
			transform: translateY(-3px);
		}
	}
	.log {
		display: flex;
		flex-direction: column;
		align-items: flex-start;
		gap: 2px;
	}
	.what {
		overflow-wrap: anywhere;
	}
	.more {
		display: flex;
		justify-content: center;
		margin-top: var(--s3);
	}
	@media (prefers-reduced-motion: reduce) {
		.calm-icon {
			animation: none;
		}
	}
</style>
