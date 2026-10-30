# -*- coding: utf-8 -*-

# Copyright 2026 Mike Fährmann
#
# This program is free software; you can redistribute it and/or modify
# it under the terms of the GNU General Public License version 2 as
# published by the Free Software Foundation.


SubredditPostQuery = """\
query SubredditPostQuery(
    $url: String!
) {
    getPost(
        data: { url: $url }
    ) {
        __typename id url title subredditId subredditTitle subredditUrl
        redditPath isNsfw hasAudio fullLengthSource gfycatSource redgifsSource
        ownerAvatar username displayName favoriteCount isPaid tags
        commentsCount commentsRepliesCount isFavorite
        albumContent { mediaSources { url width height isOptimized } }
        mediaSources { url width height isOptimized }
        blurredMediaSources { url width height isOptimized }
    }
}
"""

RedditUserPostsQuery = """\
query RedditUserPostsQuery(
    $username: String!
    $iterator: String
    $limit: Int!
    $filter: GalleryFilter
    $sortBy: GallerySortBy
    $nsfw: NsfwFilter!
) {
    getRedditUserPosts(
        data: {
            username: $username
            iterator: $iterator
            limit: $limit
            filter: $filter
            sortBy: $sortBy
            nsfw: $nsfw
        }
    ) {
        iterator items {
            __typename id url title reddit_posted_by subredditId
            subredditTitle subredditUrl subredditIsFollowing redditPath isNsfw
            hasAudio fullLengthSource gfycatSource redgifsSource ownerAvatar
            username displayName favoriteCount isPaid tags commentsCount
            commentsRepliesCount duration createdAt isFavorite
            albumContent { mediaSources { url width height isOptimized type } }
            mediaSources { url width height isOptimized type }
            blurredMediaSources { url width height isOptimized type }
        }
    }
}
"""

SubredditQuery = """\
query SubredditQuery(
    $url: String!
    $iterator: String
    $sortBy: GallerySortBy
    $filter: GalleryFilter
    $limit: Int!
) {
    getSubreddit(
        data: {
            url: $url,
            iterator: $iterator,
            filter: $filter,
            limit: $limit,
            sortBy: $sortBy
        }
    ) {
        __typename id url title secondaryTitle description createdAt isNsfw
        subscribers isComplete itemCount videoCount pictureCount albumCount
        isPaid username tags isFollowing
        banner { url width height isOptimized }
        children {
            iterator items {
                __typename id url title subredditId subredditTitle subredditUrl
                redditPath isNsfw hasAudio fullLengthSource gfycatSource
                redgifsSource ownerAvatar username displayName favoriteCount
                isPaid tags commentsCount commentsRepliesCount isFavorite
                albumContent { mediaSources { url width height isOptimized } }
                mediaSources { url width height isOptimized }
                blurredMediaSources { url width height isOptimized }
            }
        }
    }
}
"""

SubredditChildrenQuery = """\
query SubredditChildrenQuery(
    $subredditId: Int!
    $iterator: String
    $filter: GalleryFilter
    $sortBy: GallerySortBy
    $limit: Int!
    $nsfw: NsfwFilter
) {
    getSubredditChildren(
        data: {
            subredditId: $subredditId,
            iterator: $iterator,
            filter: $filter,
            sortBy: $sortBy,
            limit: $limit,
            nsfw: $nsfw
        },
    ) {
        iterator items {
            __typename id url title subredditId subredditTitle subredditUrl
            redditPath isNsfw hasAudio fullLengthSource gfycatSource
            redgifsSource ownerAvatar username displayName favoriteCount isPaid
            tags commentsCount commentsRepliesCount isFavorite
            albumContent { mediaSources { url width height isOptimized } }
            mediaSources { url width height isOptimized }
            blurredMediaSources { url width height isOptimized }
        }
    }
}
"""

GetFollowingSubreddits = """\
query GetFollowingSubreddits(
    $iterator: String,
    $limit: Int!,
    $filter: GalleryFilter,
    $nsfw: NsfwFilter,
    $sortBy: GallerySortBy
) {
    getFollowingSubreddits(
        data: {
            nsfw: $nsfw
            limit: $limit
            filter: $filter
            iterator: $iterator
            sortBy: $sortBy
        }
    ) {
        iterator items {
            __typename id url title secondaryTitle description createdAt isNsfw
            subscribers isComplete itemCount videoCount pictureCount albumCount
            isFollowing
        }
    }
}
"""

LoginQuery = """\
query LoginQuery(
    $username: String!,
    $password: String!
) {
    login(
        username: $username,
        password: $password
    ) {
        username token expiresAt isAdmin status isPremium
    }
}
"""

ItemTypeQuery = """\
query ItemTypeQuery(
    $url: String!
) {
    getItemType(
        url: $url
    )
}
"""
