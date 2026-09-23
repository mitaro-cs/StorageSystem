<script lang="ts">
	import { Paperclip, MessageCircle, EyeOff, CloudOff } from '@lucide/svelte';
	import type { Homework } from '$lib/types';
	import { fmtDue } from '$lib/format';
	import SubjectTag from '$lib/ui/SubjectTag.svelte';
	import DoneToggle from '$lib/ui/DoneToggle.svelte';

	let {
		item,
		now = Date.now(),
		ontoggle
	}: { item: Homework; now?: number; ontoggle: (h: Homework, done: boolean) => void } = $props();

	const overdue = $derived(!item.done && item.dueAt < now);
	const soon = $derived(!item.done && !overdue && item.dueAt - now < 24 * 3600 * 1000);
</script>

<div class="hw" class:done={item.done}>
	<DoneToggle done={item.done} label={item.title} onchange={(v) => ontoggle(item, v)} />
	<div class="main">
		<a href="/homework/{item.id}" class="title">{item.title}</a>
		<div class="meta">
			<SubjectTag {...item.subject} />
			{#if item.attachments.length}<span class="faint"><Paperclip size={13} /></span>{/if}
			{#if item.comments}<span class="faint small row"
					><MessageCircle size={13} />{item.comments}</span
				>{/if}
			{#if item.hidden}<span class="chip"><EyeOff size={12} /> скрыто</span>{/if}
			{#if item.pending}<span
					class="chip amber"
					title="Создано без сети — уйдёт на сервер, когда появится интернет"
					><CloudOff size={12} /> ждёт отправки</span
				>{/if}
		</div>
	</div>
	<span class="due num" class:overdue class:soon>{fmtDue(item.dueAt, now)}</span>
</div>

<style>
	.hw {
		display: flex;
		align-items: center;
		gap: 14px;
		padding: 14px var(--s4);
		min-height: 68px;
		background: var(--surface);
		transition: background-color var(--dur) var(--ease);
	}
	.hw:hover {
		background: color-mix(in srgb, var(--surface-2) 50%, var(--surface));
	}
	.main {
		flex: 1;
		min-width: 0;
		display: flex;
		flex-direction: column;
		gap: 3px;
	}
	.title {
		color: var(--text);
		font-size: 15.5px;
		font-weight: 600;
		overflow: hidden;
		text-overflow: ellipsis;
		white-space: nowrap;
		transition: color var(--dur) var(--ease);
	}
	.done .title {
		color: var(--text-3);
		text-decoration: line-through;
		text-decoration-color: var(--border-strong);
	}
	.meta {
		display: flex;
		align-items: center;
		gap: 10px;
	}
	.row {
		gap: 3px;
	}
	.due {
		flex: none;
		font-size: 13px;
		color: var(--text-2);
		text-align: right;
	}
	.due.soon {
		color: var(--amber);
		font-weight: 600;
	}
	.due.overdue {
		color: var(--danger);
		font-weight: 600;
	}
	@media (max-width: 520px) {
		.hw {
			align-items: flex-start;
			flex-wrap: wrap;
		}
		.due {
			width: 100%;
			padding-left: 38px;
			text-align: left;
		}
	}
</style>
