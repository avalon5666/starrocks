// Copyright 2021-present StarRocks, Inc. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     https://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

#pragma once

#include "column/object_column.h"
#include "column/vectorized_fwd.h"
#include "exprs/agg/aggregate.h"
#include "exprs/agg/aggregate_traits.h"
#include "gutil/casts.h"
#include "types/bitmap_value.h"

namespace starrocks {

class BitmapUnionAggregateFunction final
        : public AggregateFunctionBatchHelper<BitmapValue, BitmapUnionAggregateFunction> {
public:
    bool is_exception_safe() const override { return false; }

    void reset(FunctionContext* ctx, const Columns& args, AggDataPtr __restrict state) const override {
        this->data(state).clear();
    }

    void update(FunctionContext* ctx, const Column** columns, AggDataPtr state, size_t row_num) const override {
        const auto* col = down_cast<const BitmapColumn*>(columns[0]);
        this->data(state) |= *(col->get_object(row_num));
    }

    void merge(FunctionContext* ctx, const Column* column, AggDataPtr __restrict state, size_t row_num) const override {
        const auto* col = down_cast<const BitmapColumn*>(column);
        DCHECK(col->is_object());
        this->data(state) |= *(col->get_object(row_num));
    }

    void serialize_to_column(FunctionContext* ctx, ConstAggDataPtr __restrict state, Column* to) const override {
        auto* col = down_cast<BitmapColumn*>(to);
        auto& bitmap = const_cast<BitmapValue&>(this->data(state));
        col->append(std::move(bitmap));
    }

    void update_batch_single_state_with_frame(FunctionContext* ctx, AggDataPtr __restrict state, const Column** columns,
                                              int64_t peer_group_start, int64_t peer_group_end, int64_t frame_start,
                                              int64_t frame_end) const override {
        const auto* col = down_cast<const BitmapColumn*>(columns[0]);
        for (size_t i = frame_start; i < frame_end; ++i) {
            this->data(state) |= *(col->get_object(i));
        }
    }

    void convert_to_serialize_format(FunctionContext* ctx, const Columns& src, size_t chunk_size,
                                     ColumnPtr* dst) const override {
        *dst = src[0];
    }

    void finalize_to_column(FunctionContext* ctx, ConstAggDataPtr __restrict state, Column* to) const override {
        auto* col = down_cast<BitmapColumn*>(to);
        auto& bitmap = const_cast<BitmapValue&>(this->data(state));
        col->append(std::move(bitmap));
    }

    void get_values(FunctionContext* ctx, ConstAggDataPtr __restrict state, Column* dst, size_t start,
                    size_t end) const override {
        auto* col = down_cast<BitmapColumn*>(dst);
        for (size_t i = start; i < end; i++) {
            AggDataTypeTraits<TYPE_OBJECT>::assign_value(col, i, this->data(state));
        }
    }

    std::string get_name() const override { return "bitmap_union"; }
};

} // namespace starrocks
