<script lang="ts">
	import { CloudOff, EyeOff } from '@lucide/svelte';
	import { offline } from '$lib/offline/engine';
	import { del, get, post, put as putApi } from '$lib/api';
	import { fmtAgo } from '$lib/format';
	import { shortNames } from '$lib/names';
	import { peek, put } from '$lib/cache';
	import { currentGroup, groups, session } from '$lib/session.svelte';
	import { report } from '$lib/content/moderate';
	import { fly, slide } from '$lib/motion';
	import { toastError } from '$lib/toasts.svelte';
	import type { Comment, Member } from '$lib/types';
	import Author from './Author.svelte';
	import Button from '$lib/ui/Button.svelte';
	import Prose from '$lib/ui/Prose.svelte';

	let { base, canComment = true }: { base: string; canComment?: boolean } = $props();

	let items = $state<Comment[]>([]);
	let loaded = $state(false);
	let members = $state<Member[]>([]);

	// Подписи — только имена; если имя повторяется среди участников группы или в обсуждении,
	// к нему добавляется фамилия.
	const names = $derived(
		shortNames([
			...members
				.filter((m) => m.status !== 'deleted')
				.map((m) => ({ id: m.userId, displayName: m.displayName })),
			...items.map((c) => c.author)
		])
	);

	$effect(() => {
		const g = currentGroup()?.id ?? groups()[0]?.id;
		if (!g) return;
		const key = `members:${g}`;
		members = peek<Member[]>(key) ?? [];
		get<Member[]>(`/api/groups/${g}/members`)
			.then((m) => (members = put(key, m)))
			.catch(() => {});
	});
	let text = $state('');
	let busy = $state(false);

	$effect(() => {
		void offline.version;
		get<Comment[]>(`${base}/comments`)
			.then((c) => ((items = c), (loaded = true)))
			.catch(() => (loaded = true));
	});

	async function send(e: SubmitEvent) {
		e.preventDefault();
		if (!text.trim()) return;
		busy = true;
		try {
			items.push(await post<Comment>(`${base}/comments`, { body: text }));
			text = '';
		} catch (err) {
			toastError(err);
		} finally {
			busy = false;
		}
	}

	/** Скрыть или вернуть (модератор): скрытый видят только модераторы и автор. */
	async function toggleHidden(c: Comment) {
		try {
			await putApi(`/api/comments/${c.id}/hidden`, { value: !c.hidden });
			c.hidden = !c.hidden;
		} catch (err) {
			toastError(err);
		}
	}

	async function remove(c: Comment) {
		try {
			await del(`/api/comments/${c.id}`);
			items = items.filter((x) => x.id !== c.id);
		} catch (err) {
			toastError(err);
		}
	}
</script>

<section class="comments" aria-label="Комментарии">
	<h2>
		Комментарии {#if loaded}<span class="faint num">{items.length}</span>{/if}
	</h2>
	{#each items as c (c.id)}
		<div class="comment" in:fly={{ y: 6 }} out:slide>
			<div class="head">
				<Author person={c.author} size={24} label={names.get(c.author.id)} />
				<span class="faint small num">{fmtAgo(c.createdAt)}</span>
				{#if c.pending}<span
						class="chip amber"
						title="Создано без сети — уйдёт на сервер, когда появится интернет"
						><CloudOff size={12} /> ждёт отправки</span
					>{/if}
				{#if c.hidden}<span class="chip" title="Видят только модераторы и автор"
						><EyeOff size={12} /> скрыт</span
					>{/if}
				<span class="spacer"></span>
				{#if c.canHide}
					<button class="link small" onclick={() => toggleHidden(c)}
						>{c.hidden ? 'Вернуть' : 'Скрыть'}</button
					>
				{/if}
				{#if c.canDelete}
					<button class="link small" onclick={() => remove(c)}>Удалить</button>
				{:else if c.id > 0 && c.author.id !== session.me?.user.id}
					<button class="link small faint" onclick={() => report('comment', c.id)}
						>Пожаловаться</button
					>
				{/if}
			</div>
			<Prose html={c.bodyHtml} class="text {c.hidden ? 'dim' : ''}" />
		</div>
	{/each}
	{#if canComment}
		<form onsubmit={send} class="new">
			<label class="sr-only" for="comment">Комментарий</label>
			<textarea
				id="comment"
				class="textarea"
				rows="2"
				bind:value={text}
				placeholder="Написать комментарий…"
				maxlength="4000"></textarea>
			<div class="row">
				<span class="spacer"></span><Button
					type="submit"
					variant="primary"
					size="s"
					loading={busy}
					disabled={!text.trim()}>Отправить</Button
				>
			</div>
		</form>
	{/if}
</section>

<style>
	.comments {
		display: flex;
		flex-direction: column;
		gap: var(--s3);
		margin-top: var(--s5);
	}
	h2 {
		font-size: 17px;
	}
	.comment {
		display: flex;
		flex-direction: column;
		gap: 6px;
		padding: var(--s3) var(--s4);
		background: var(--surface);
		border-radius: var(--r);
		box-shadow: var(--shadow-1);
	}
	.comment :global(.text) {
		padding-left: 32px;
	}
	.comment :global(.text.dim) {
		opacity: 0.6;
	}
	.head {
		flex-wrap: wrap;
	}
	.head {
		display: flex;
		align-items: center;
		gap: 8px;
	}
	.link {
		border: 0;
		background: none;
		color: var(--text-3);
		padding: 0;
	}
	.link:hover {
		color: var(--danger);
	}
	.new {
		display: flex;
		flex-direction: column;
		gap: 8px;
	}
	.new .textarea {
		min-height: 72px;
	}
</style>
