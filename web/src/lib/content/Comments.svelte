<script lang="ts">
	import { CloudOff } from '@lucide/svelte';
	import { offline } from '$lib/offline/engine';
	import { del, get, post } from '$lib/api';
	import { fmtAgo } from '$lib/format';
	import { fly, slide } from '$lib/motion';
	import { toastError } from '$lib/toasts.svelte';
	import type { Comment } from '$lib/types';
	import Author from './Author.svelte';
	import Button from '$lib/ui/Button.svelte';
	import Prose from '$lib/ui/Prose.svelte';

	let { base, canComment = true }: { base: string; canComment?: boolean } = $props();

	let items = $state<Comment[]>([]);
	let loaded = $state(false);
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
				<Author person={c.author} size={24} />
				<span class="faint small num">{fmtAgo(c.createdAt)}</span>
				{#if c.pending}<span
						class="chip amber"
						title="Создано без сети — уйдёт на сервер, когда появится интернет"
						><CloudOff size={12} /> ждёт отправки</span
					>{/if}
				<span class="spacer"></span>
				{#if c.canDelete}
					<button class="link small" onclick={() => remove(c)}>Удалить</button>
				{/if}
			</div>
			<Prose html={c.bodyHtml} class="text" />
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
