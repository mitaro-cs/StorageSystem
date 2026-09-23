<script lang="ts">
	import { goto } from '$app/navigation';
	import { page } from '$app/state';
	import { ArrowLeft, EyeOff } from '@lucide/svelte';
	import { del, get, put } from '$lib/api';
	import { fmtAgo, fmtDue, relativeDay } from '$lib/format';
	import { can, isMulti } from '$lib/session.svelte';
	import { toast, toastError } from '$lib/toasts.svelte';
	import type { Homework } from '$lib/types';
	import { toggleDone } from '$lib/content/homework';
	import Author from '$lib/content/Author.svelte';
	import Comments from '$lib/content/Comments.svelte';
	import HomeworkComposer from '$lib/content/HomeworkComposer.svelte';
	import SubjectTag from '$lib/ui/SubjectTag.svelte';
	import DoneToggle from '$lib/ui/DoneToggle.svelte';
	import Menu, { type MenuItem } from '$lib/ui/Menu.svelte';
	import Skeleton from '$lib/ui/Skeleton.svelte';
	import Empty from '$lib/ui/Empty.svelte';
	import Prose from '$lib/ui/Prose.svelte';

	let item = $state<Homework | null>(null);
	let missing = $state(false);
	let composer = $state(false);
	const now = Date.now();

	async function load() {
		try {
			item = await get<Homework>(`/api/homework/${page.params.id}`);
		} catch {
			missing = true;
		}
	}

	$effect(() => {
		void page.params.id;
		load();
	});

	const actions = $derived.by((): MenuItem[] => {
		if (!item) return [];
		const h = item;
		const out: MenuItem[] = [];
		if (h.can.edit) out.push({ label: 'Изменить', onclick: () => (composer = true) });
		if (h.can.hide)
			out.push({
				label: h.hidden ? 'Вернуть' : 'Скрыть',
				onclick: async () => {
					try {
						await put(`/api/homework/${h.id}/hidden`, { value: !h.hidden });
						load();
					} catch (e) {
						toastError(e);
					}
				}
			});
		if (h.can.delete)
			out.push({
				label: 'Удалить',
				danger: true,
				onclick: async () => {
					if (!confirm('Удалить задание?')) return;
					try {
						await del(`/api/homework/${h.id}`);
						toast('Задание удалено', 'ok');
						goto('/homework', { replaceState: true });
					} catch (e) {
						toastError(e);
					}
				}
			});
		return out;
	});

	const overdue = $derived(item ? !item.done && item.dueAt < now : false);
</script>

<svelte:head><title>{item?.title ?? 'Задание'} · groupbase</title></svelte:head>

<a
	class="back"
	href="/homework"
	onclick={(e) => {
		if (history.length > 1) {
			e.preventDefault();
			history.back();
		}
	}}><ArrowLeft size={16} /> Задания</a
>

{#if missing}
	<div class="card">
		<Empty title="Задание не найдено" text="Его удалили или оно адресовано другой группе." />
	</div>
{:else if !item}
	<Skeleton lines={6} />
{:else}
	<article class="card hw">
		<div class="meta">
			<SubjectTag {...item.subject} />
			{#if isMulti()}{#each item.groups as g (g.id)}<span class="chip">{g.name}</span>{/each}{/if}
			{#if item.hidden}<span class="chip"><EyeOff size={13} /> Скрыто</span>{/if}
			<span class="spacer"></span>
			<Menu items={actions} />
		</div>
		<h1>{item.title}</h1>
		<div class="due-box" class:overdue class:done={item.done}>
			<DoneToggle
				done={item.done}
				label={item.title}
				onchange={(v) => item && toggleDone(item, v)}
			/>
			<div>
				<strong class="num">{fmtDue(item.dueAt, now)}</strong>
				<span class="small"
					>{item.done
						? 'Отмечено как выполненное — видно только вам'
						: overdue
							? 'Срок прошёл'
							: `Сдать ${relativeDay(item.dueAt, now)}`}</span
				>
			</div>
		</div>
		{#if item.bodyHtml}<Prose html={item.bodyHtml} />{/if}
		<footer class="row faint small">
			<Author person={item.author} size={22} />
			<span class="num">· {fmtAgo(item.createdAt)}</span>
		</footer>
	</article>
	<Comments
		base="/api/homework/{item.id}"
		canComment={item.groups.some((g) => can('comment', g.id))}
	/>
	<HomeworkComposer bind:open={composer} edit={item} onsaved={(h) => (item = h)} />
{/if}

<style>
	.back {
		display: inline-flex;
		align-items: center;
		gap: 6px;
		margin-bottom: var(--s4);
		color: var(--text-2);
		font-size: 14px;
		font-weight: 550;
	}
	.hw {
		display: flex;
		flex-direction: column;
		gap: var(--s4);
		padding: var(--s5);
	}
	.meta {
		display: flex;
		align-items: center;
		gap: 8px;
		flex-wrap: wrap;
	}
	h1 {
		font-size: 24px;
	}
	.due-box {
		display: flex;
		align-items: center;
		gap: 14px;
		padding: 12px 16px;
		border-radius: var(--r);
		background: var(--amber-soft);
		color: var(--amber);
	}
	.due-box div {
		display: flex;
		flex-direction: column;
	}
	.due-box strong {
		color: var(--text);
	}
	.due-box.overdue {
		background: var(--danger-soft);
		color: var(--danger);
	}
	.due-box.done {
		background: var(--ok-soft);
		color: var(--ok);
	}
	footer {
		gap: 6px;
	}
</style>
