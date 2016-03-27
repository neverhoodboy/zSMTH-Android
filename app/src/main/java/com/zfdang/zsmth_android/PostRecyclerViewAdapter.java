package com.zfdang.zsmth_android;

import android.support.v7.widget.RecyclerView;
import android.text.Html;
import android.text.Spanned;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import com.zfdang.zsmth_android.listeners.OnPostFragmentInteractionListener;
import com.zfdang.zsmth_android.models.Post;

import java.util.List;

/**
 * used by HotPostFragment & BoardPostFragment
 */
public class PostRecyclerViewAdapter extends RecyclerView.Adapter<PostRecyclerViewAdapter.ViewHolder> {

    private final List<Post> mPosts;
    private final OnPostFragmentInteractionListener mListener;

    public PostRecyclerViewAdapter(List<Post> posts, OnPostFragmentInteractionListener listener) {
        mPosts = posts;
        mListener = listener;
    }

    @Override
    public ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.post_item, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(final ViewHolder holder, int position) {
        holder.mPost = mPosts.get(position);
        Post post = holder.mPost;

        holder.mPostAuthor.setText(post.getAuthor());

        String formattedText = post.getTextContent();
        Spanned result = Html.fromHtml(formattedText);
        holder.mPostContent.setText(result);

        holder.mPostIndex.setText(String.format("第%d楼", position));

        holder.mView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (null != mListener) {
                    // Notify the active callbacks interface (the activity, if the
                    // fragment is attached to one) that an item has been selected.
                    mListener.onPostFragmentInteraction(holder.mPost);
                }
            }
        });
    }

    @Override
    public int getItemCount() {
        return mPosts.size();
    }

    public class ViewHolder extends RecyclerView.ViewHolder {
        public final View mView;
        public final TextView mPostAuthor;
        public final TextView mPostIndex;
        public final TextView mPostPublishDate;
        public final TextView mPostContent;
        public Post mPost;

        public ViewHolder(View view) {
            super(view);
            mView = view;
            mPostAuthor = (TextView) view.findViewById(R.id.post_author);
            mPostIndex = (TextView) view.findViewById(R.id.post_index);
            mPostPublishDate = (TextView) view.findViewById(R.id.post_publish_date);
            mPostContent = (TextView) view.findViewById(R.id.post_content);
        }

        @Override
        public String toString() {
            return mPost.toString();
        }
    }
}
